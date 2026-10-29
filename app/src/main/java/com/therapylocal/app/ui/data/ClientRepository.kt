package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SessionRow
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.jobs.Jobs
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

    /** Deletes the client and all its stored data (audio not processed yet included), and drops it from the pipeline queue state. */
    fun deleteClient(id: String) {
        // Its jobs cascade with it; dropping them first also tells the queue's observers.
        Jobs.queue(appContext).dropClient(id)
        val sessions = store.sessions(id).map { it.id }.toSet()
        deleteAudio { it in sessions }
        val removed = store.deleteClient(id)
        AppLog.i(TAG, "delete_client_verified", LogCtx.NONE, "client" to id, "removed" to removed, "facts_left" to store.facts(id).size, "sessions_left" to store.sessions(id).size)
    }

    fun observations(session: String): String = store.observations(session)

    fun saveObservations(session: String, text: String) = store.setObservations(session, text)

    /** True while the AI files are still downloading: new sessions are kept and processed afterwards. */
    fun waitingForSetup(): Boolean = !ModelSetup.isReady(appContext)

    /** Deletes one session with everything stored for it (facts first seen in it move to their next mention), its jobs and its audio. */
    fun deleteSession(client: String, session: String) {
        Jobs.queue(appContext).dropSession(session)
        deleteAudio { it == session }
        val r = store.deleteSession(session)
        AppLog.i(TAG, "delete_session_verified", LogCtx.NONE, "client" to client, "session" to session, "promoted_facts" to r.promoted, "removed_facts" to r.deleted)
    }

    fun sessions(client: String): List<SessionRow> = store.sessions(client)

    /** The audio files of the recordings of the matching sessions; their rows go with the sessions. */
    private fun deleteAudio(ofSession: (String) -> Boolean) {
        store.recordings().filter { r -> r.sessionId?.let(ofSession) == true }.forEach { PendingAudio.delete(appContext, it.id) }
    }

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
