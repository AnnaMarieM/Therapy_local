package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.data.FactRow
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.FactItem
import com.therapylocal.app.ui.models.FactOccurrence
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SearchHit
import com.therapylocal.app.ui.models.SessionItem
import com.therapylocal.app.ui.models.TranscriptLine

/**
 * What the UI knows about clients, read from [MindStore] (the on-device long-term memory). The UI never touches SQL.
 * A client id is the lowercase memory client name, e.g. "george"; a [Patient.id] built here is that id.
 * Blocking calls: run them off the main thread. [open] is the vault's database; tests give their own.
 */
class ClientRepository(context: Context, private val open: (Context) -> MindStore = { MindStore.get(it) }) {
    private val appContext = context.applicationContext
    private val store get() = open(appContext)

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

    /** True while the AI files are still downloading: new sessions are kept and processed afterwards. */
    fun waitingForSetup(): Boolean = !ModelSetup.isReady(appContext)

    /** Deletes one session with everything stored for it (facts first seen in it move to their next mention), its jobs and its audio. */
    fun deleteSession(client: String, session: String) {
        Jobs.queue(appContext).dropSession(session)
        deleteAudio { it == session }
        val r = store.deleteSession(session)
        AppLog.i(TAG, "delete_session_verified", LogCtx.NONE, "client" to client, "session" to session, "promoted_facts" to r.promoted, "removed_facts" to r.deleted)
    }

    /** The audio files of the recordings of the matching sessions; their rows go with the sessions. */
    private fun deleteAudio(ofSession: (String) -> Boolean) {
        store.recordings().filter { r -> r.sessionId?.let(ofSession) == true }.forEach { PendingAudio.delete(appContext, it.id) }
    }

    /** The transcript segment a fact or cue points at, or null if not stored. */
    fun sourceChunk(session: String, segIdx: Int): String? = store.segmentsAround(session, segIdx, 0).firstOrNull()?.text

    /** All clients as UI patients. */
    fun patients(): List<Patient> = store.clients().map { patient(it.id, it.name) }

    fun patient(client: String): Patient = patient(client, store.clients().firstOrNull { it.id == client }?.name)

    private fun patient(client: String, name: String?): Patient {
        val sessions = store.sessions(client)
        val dates = sessions.associate { it.id to date(it.startedAt) }
        val rows = store.facts(client)
        val items = sessions.map { s ->
            SessionItem(
                id = s.id,
                date = dates.getValue(s.id),
                duration = store.transcriptLengthMs(s.id).let { if (it > 0) SessionLogic.formatMinSec(it / 1000) else "-" },
            )
        }
        return Patient(
            id = client,
            name = name ?: client.replaceFirstChar { it.uppercase() },
            lastSession = sessions.lastOrNull()?.let { dates.getValue(it.id) } ?: "-",
            sessionCount = sessions.size,
            sessions = items.asReversed(), // newest first, as the profile lists them
            facts = rows.map { it.toItem(dates) },
        )
    }

    /** Where the fact was said, first occurrence first: each with the transcript within [CONTEXT_SEGMENTS] segments of it. */
    fun occurrences(factId: Long): List<FactOccurrence> = store.factOccurrences(factId).map { o ->
        FactOccurrence(o.sessionId, date(o.startedAt), o.first, o.segIdx, store.segmentsAround(o.sessionId, o.segIdx, CONTEXT_SEGMENTS).map { it.toLine() })
    }

    fun transcript(session: String): List<TranscriptLine> = store.segments(session).map { it.toLine() }

    /**
     * The client's transcript windows closest to [vec] (a query embedding, text or audio), then [text] matched in fact headings and
     * contents. A null [vec] searches the facts only; a non-null one also ranks the facts by meaning when [text] is null (voice search).
     */
    fun search(client: String, vec: FloatArray?, text: String?): List<SearchHit> {
        val dates = store.sessions(client).associate { it.id to date(it.startedAt) }
        val windows = vec?.let { store.nearestWindows(client, it, SEARCH_WINDOWS) }.orEmpty().map { (w, _) ->
            SearchHit(w.sessionId, dates[w.sessionId].orEmpty(), title = null, snippet = snippet(w.text), segFrom = w.segFrom, segTo = w.segTo)
        }
        val facts = if (text != null) {
            val q = text.trim().lowercase()
            store.facts(client).filter { it.heading.lowercase().contains(q) || it.content.lowercase().contains(q) }.take(SEARCH_TEXT_FACTS)
        } else {
            vec?.let { store.nearestFacts(client, it, SEARCH_FACTS).map { (f, _) -> f } }.orEmpty()
        }
        return windows + facts.map { f ->
            SearchHit(f.sessionId, dates[f.sessionId].orEmpty(), f.heading, f.content, f.segIdx - CONTEXT_SEGMENTS, f.segIdx + CONTEXT_SEGMENTS, f.segIdx)
        }
    }

    /** Segments [from]..[to] of [session]. */
    fun transcript(session: String, from: Int, to: Int): List<TranscriptLine> {
        // segmentsAround reads a centred range; it covers [from]..[to] (one more when the span is even), trimmed here.
        val r = (to - from + 1) / 2
        return store.segmentsAround(session, from + r, r).filter { it.idx in from..to }.map { it.toLine() }
    }

    private fun FactRow.toItem(dates: Map<String, String>) = FactItem(id, heading, content, dates[sessionId].orEmpty(), mentionCount)

    private fun SegmentRow.toLine() = TranscriptLine(idx, SessionLogic.formatMinSec(startMs / 1000), text)

    private fun snippet(text: String) = if (text.length <= SNIPPET_CHARS) text else text.take(SNIPPET_CHARS).substringBeforeLast(' ') + "…"

    private fun date(millis: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))

    companion object {
        const val TAG = "ClientRepository"
        const val CONTEXT_SEGMENTS = 3
        private const val SEARCH_WINDOWS = 5
        private const val SEARCH_FACTS = 3
        private const val SEARCH_TEXT_FACTS = 10
        private const val SNIPPET_CHARS = 180
    }
}
