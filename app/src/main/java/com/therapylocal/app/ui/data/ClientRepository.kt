package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SessionRow
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.memory.MemoryProcessor
import com.therapylocal.app.memory.toFact
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SessionRecord

/**
 * What the UI knows about clients, read from [MindStore] (the on-device long-term memory). The UI never touches SQL.
 * A client id is the lowercase memory client name, e.g. "george"; a [Patient.id] built here is that id.
 * Blocking calls: run them off the main thread.
 */
class ClientRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store get() = MindStore.get(appContext)

    fun clients(): List<String> = store.clients().map { it.id }

    /** Creates a client from the add-patient form and returns its UI patient. The id is a free slug of the name. */
    fun createClient(name: String): Patient {
        val id = ClientInput.uniqueId(ClientInput.slug(name), clients().toSet())
        store.createClient(id, name.trim())
        return patient(id)
    }

    /** Deletes the client and all its stored data, and drops it from the pipeline queue state. */
    fun deleteClient(id: String) {
        // Its jobs cascade with it; dropping them first also tells the queue's observers.
        Jobs.queue(appContext).dropClient(id)
        val removed = store.deleteClient(id)
        // Session ids are "<client>_…", so a client's transcripts and extraction caches are recognised by the prefix.
        listOf("in", "cache").forEach { dir ->
            java.io.File(MemoryFiles.secureRoot(appContext), dir).listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.deleteRecursively() }
        }
        AppLog.i(TAG, "delete_client_verified", LogCtx.NONE, "client" to id, "removed" to removed, "facts_left" to store.facts(id).size, "sessions_left" to store.sessions(id).size)
    }

    fun facts(client: String): List<Fact> = store.facts(client).map { it.toFact() }

    fun sessionFacts(client: String, session: String): List<Fact> = facts(client).filter { it.session == session }

    fun observations(session: String): String = store.observations(session)

    fun saveObservations(session: String, text: String) = store.setObservations(session, text)

    /** Queues the session transcript for fact extraction (runs in the background service). False for a blank transcript. */
    fun processSession(client: String, session: String, date: String, transcript: String): Boolean =
        MemoryProcessor.enqueueSession(appContext, client, session, date, transcript)

    /** Queues a recording whose audio is still to be transcribed (made before setup finished); the background service does that, then processes the session. */
    fun processRecording(client: String, session: String, date: String, recordingId: Long) =
        MemoryProcessor.enqueueRecording(appContext, client, session, date, recordingId)

    /** True while the AI files are still downloading: new sessions are kept and processed afterwards. */
    fun waitingForSetup(): Boolean = !ModelSetup.isReady(appContext)

    /** Deletes one session with everything stored for it (facts first seen in it move to their next mention), the queued job, its transcript and its extraction cache. */
    fun deleteSession(client: String, session: String) {
        val queue = Jobs.queue(appContext)
        queue.snapshot.value.jobs.filter { it.sessionId == session }.forEach { j -> j.recordingId?.let { PendingAudio.delete(appContext, it) } }
        queue.dropSession(session)
        MemoryFiles.transcript(appContext, "$session.md").delete()
        MemoryFiles.extractCache(appContext, session).deleteRecursively()
        val r = store.deleteSession(session)
        AppLog.i(TAG, "delete_session_verified", LogCtx.NONE, "client" to client, "session" to session, "promoted_facts" to r.promoted, "removed_facts" to r.deleted)
    }

    fun sessions(client: String): List<SessionRow> = store.sessions(client)

    /** The transcript segment a fact or cue points at, or null if not stored. */
    fun sourceChunk(session: String, segIdx: Int): String? = store.segmentsAround(session, segIdx, 0).firstOrNull()?.text

    /** All clients as UI patients. */
    fun patients(): List<Patient> = store.clients().map { patient(it.id) }

    fun patient(client: String): Patient {
        val facts = store.facts(client)
        val sessions = sessions(client)
        val bySession = facts.groupBy { it.sessionId }
        val records = sessions.map { s ->
            val sf = bySession[s.id].orEmpty()
            SessionRecord(
                id = s.id,
                date = date(s),
                duration = s.endedAt?.let { "${(it - s.startedAt) / 60_000} min" } ?: "-",
                summary = sf.take(4).joinToString(" ") { it.content }.ifEmpty { "No notes extracted yet." },
                verbatimQuotes = sf.map { it.evidence }.filter { it.isNotBlank() }.take(3),
            )
        }
        val last = sessions.lastOrNull()
        return Patient(
            id = client,
            name = store.clients().firstOrNull { it.id == client }?.name ?: client.replaceFirstChar { it.uppercase() },
            age = 0, // age is no longer stored
            presenting = "",
            lastSession = last?.let { date(it) } ?: "-",
            sessionCount = sessions.size,
            preSessionNote = last?.let { l -> bySession[l.id].orEmpty().take(3).joinToString(" ") { it.content } }.orEmpty(),
            sessions = records.asReversed(), // newest first, as the profile lists them
            facts = facts.map { f -> if (f.mentionCount > 0) "${f.content} (x${f.mentionCount + 1})" else f.content },
        )
    }

    private fun date(s: SessionRow): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(s.startedAt))

    companion object {
        const val TAG = "ClientRepository"
    }
}
