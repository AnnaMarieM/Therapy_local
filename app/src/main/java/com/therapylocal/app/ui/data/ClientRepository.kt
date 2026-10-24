package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.memory.MemoryStore
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
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val demoMode: Boolean get() = prefs.getBoolean(DEMO_MODE_KEY, false)

    fun clients(): List<String> = store.clients()

    fun facts(client: String): List<Fact> = store.facts(client)

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
        val real = clients().map(::patient)
        return if (real.isEmpty() && !demoMode) SeedData.getInitialPatients() else real
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
        return Patient(
            id = client,
            name = client.replaceFirstChar { it.uppercase() },
            age = 0,
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
        const val DEMO_MODE_KEY = "demoMode"
    }
}
