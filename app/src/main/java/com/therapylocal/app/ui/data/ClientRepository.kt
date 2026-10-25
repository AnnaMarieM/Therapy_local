package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.memory.DemoMemory
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryProcessor
import com.therapylocal.app.memory.MemoryQueue
import com.therapylocal.app.memory.MemoryStore
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SeedData
import com.therapylocal.app.ui.models.SessionRecord

/** A fact that came up in more than one session: [count] is the number of sessions it was said in (first one included). */
data class Theme(val fact: Fact, val count: Int)

/**
 * What the UI knows about clients, read from [MemoryStore] (the on-device long-term memory). The UI never touches SQL.
 * A client id is the lowercase memory client name, e.g. "george"; a [Patient.id] built here is that id.
 * Blocking calls: run them off the main thread.
 */
class ClientRepository(context: Context, private val store: MemoryStore = MemoryStore(context)) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val demoMode: Boolean get() = prefs.getBoolean(DEMO_MODE_KEY, false)

    fun clients(): List<String> = store.clients()

    /** Switches demo mode. Turning it on imports the bundled demo memory (debug builds ship `assets/demo_memory.json`); turning it off keeps the imported data. */
    fun setDemoMode(on: Boolean) {
        prefs.edit().putBoolean(DEMO_MODE_KEY, on).apply()
        if (on) importDemo(force = true)
    }

    /** Imports the demo asset if demo mode is on and this version of it is not imported yet (or [force]). Returns whether it imported. Blocking. */
    fun importDemo(force: Boolean = false): Boolean {
        if (!force && !demoMode) return false
        return try {
            val data = DemoMemory.parse(appContext.assets.open("demo_memory.json").bufferedReader().use { it.readText() })
            if (!force && prefs.getInt(DEMO_IMPORTED_KEY, 0) == data.version) return false
            DemoMemory.import(store, data)
            prefs.edit().putInt(DEMO_IMPORTED_KEY, data.version).putBoolean(MOCK_DISMISSED_KEY, true).apply()
            AppLog.i(TAG, "demo_imported", LogCtx.NONE, "clients" to data.clients.size, "facts" to data.clients.sumOf { it.facts.size })
            true
        } catch (e: java.io.IOException) {
            AppLog.w(TAG, "demo_asset_missing", LogCtx.NONE, "reason" to e.message)
            false
        }
    }

    /** Creates a client from the add-patient form and returns its UI patient. The id is a free slug of the name. */
    fun createClient(name: String, age: Int?, occupation: String): Patient {
        val id = ClientInput.uniqueId(ClientInput.slug(name), clients().toSet())
        store.createClient(id, name.trim(), age, occupation.trim())
        prefs.edit().putBoolean(MOCK_DISMISSED_KEY, true).apply()
        return patient(id)
    }

    /** Deletes the client and all its stored data, and drops it from the pipeline queue state. */
    fun deleteClient(id: String) {
        val r = store.deleteClient(id)
        MemoryQueue.dropClient(id)
        // Session ids are "<client>_…", so a client's transcripts and extraction caches are recognised by the prefix.
        listOf("in", "cache").forEach { dir ->
            java.io.File(MemoryFiles.secureRoot(appContext), dir).listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.deleteRecursively() }
        }
        prefs.edit().putBoolean(MOCK_DISMISSED_KEY, true).apply()
        AppLog.i(TAG, "delete_client_verified", LogCtx.NONE, "client" to id, "removed_clients" to r.clients, "removed_chunks" to r.chunks, "removed_facts" to r.facts, "removed_supports" to r.supports, "removed_sessions" to r.sessions)
    }

    /** The client id to record a session under: [p]'s id if it is stored, else [p] (a mock patient) is created as a client first. */
    fun ensureClient(p: Patient): String =
        if (p.id in clients()) p.id else createClient(p.name, null, "").id

    fun facts(client: String): List<Fact> = store.facts(client)

    fun sessionFacts(client: String, session: String): List<Fact> = facts(client).filter { it.session == session }

    fun observations(client: String, session: String): String = store.observations(client, session)

    fun saveObservations(client: String, session: String, text: String) = store.setObservations(client, session, text)

    /** Queues the session transcript for fact extraction (runs in the background service). False for a blank transcript. */
    fun processSession(client: String, session: String, date: String, transcript: String): Boolean =
        MemoryProcessor.enqueueSession(appContext, client, session, date, transcript)

    /** Queues a recording whose audio is still to be transcribed (made before setup finished); the background service does that, then processes the session. */
    fun processRecording(client: String, session: String, date: String, recordingId: Long) =
        MemoryProcessor.enqueueRecording(appContext, client, session, date, recordingId)

    /** True while the AI files are still downloading: new sessions are kept and processed afterwards. */
    fun waitingForSetup(): Boolean = !ModelSetup.isReady(appContext)

    /** Deletes one session: its facts, chunks, repeats and observations, the queued job, its transcript and its extraction cache. */
    fun deleteSession(client: String, session: String) {
        MemoryQueue.state.value.jobs.firstOrNull { it.id == session }?.takeIf { it.recordingId != 0L }?.let { PendingAudio.delete(appContext, it.recordingId) }
        MemoryQueue.dropSession(session)
        MemoryFiles.transcript(appContext, "$session.md").delete()
        MemoryFiles.extractCache(appContext, session).deleteRecursively()
        val r = store.deleteSession(client, session)
        AppLog.i(TAG, "delete_session_verified", LogCtx.NONE, "client" to client, "session" to session, "removed_chunks" to r.chunks, "removed_facts" to r.facts, "removed_supports" to r.supports)
    }

    fun sessions(client: String): List<MemoryStore.SessionInfo> = store.sessions(client)

    /** The transcript chunk a fact was extracted from (1-based chunk index within its session), or null if not stored. */
    fun sourceChunk(client: String, session: String, chunk: Int): String? = store.chunkText(client, session, chunk)

    fun sourceChunk(client: String, fact: Fact): String? = sourceChunk(client, fact.session, fact.chunk)

    /** Facts that recurred, most repeated first. */
    fun recurringThemes(client: String): List<Theme> {
        val counts = store.supportCounts(client)
        return facts(client).mapNotNull { f -> counts[f.id]?.let { Theme(f, it + 1) } }.sortedByDescending { it.count }
    }

    /** All clients as UI patients. Mock data only when the store is empty and demo mode is off. */
    fun patients(): List<Patient> {
        val real = store.listClients().map { patient(it.id) }
        return if (real.isEmpty() && !demoMode && !prefs.getBoolean(MOCK_DISMISSED_KEY, false)) SeedData.getInitialPatients() else real
    }

    fun patient(client: String): Patient {
        val facts = facts(client)
        val sessions = sessions(client)
        val counts = store.supportCounts(client)
        val bySession = facts.groupBy { it.session }
        val records = sessions.map { s ->
            val sf = bySession[s.id].orEmpty()
            SessionRecord(
                id = s.id,
                date = s.date.ifEmpty { label(s.id) },
                duration = "${s.chunks} chunks",
                summary = sf.take(4).joinToString(" ") { it.fact }.ifEmpty { "No facts extracted yet." },
                topics = sf.map { it.category }.distinct().take(3),
                verbatimQuotes = sf.map { it.evidence }.filter { it.isNotBlank() }.take(3),
            )
        }
        val last = sessions.lastOrNull()
        val row = store.listClients().firstOrNull { it.id == client }
        return Patient(
            id = client,
            name = row?.name ?: client.replaceFirstChar { it.uppercase() },
            age = 0, // age is no longer shown; the column is kept only to avoid a schema change
            presenting = facts.firstOrNull { it.category == "goal" || it.category == "emotion" }?.fact.orEmpty(),
            lastSession = last?.let { it.date.ifEmpty { label(it.id) } } ?: "-",
            sessionCount = sessions.size,
            preSessionNote = last?.let { l -> bySession[l.id].orEmpty().take(3).joinToString(" ") { it.fact } }.orEmpty(),
            sessions = records.asReversed(), // newest first, as the profile lists them
            facts = facts.map { f -> counts[f.id]?.let { "${f.fact} (x${it + 1})" } ?: f.fact },
        )
    }

    /** "george_s03" -> "Session 3" when the session has no stored date. */
    private fun label(sessionId: String): String =
        Regex("""(\d+)$""").find(sessionId)?.let { "Session ${it.value.trimStart('0').ifEmpty { "0" }}" } ?: sessionId

    companion object {
        const val TAG = "ClientRepository"
        const val DEMO_MODE_KEY = "demoMode"
        private const val DEMO_IMPORTED_KEY = "demoImportedVersion"

        /** Set once the therapist creates or deletes a client, so the mock patients never come back after the real list was emptied. */
        const val MOCK_DISMISSED_KEY = "mockDismissed"
    }
}
