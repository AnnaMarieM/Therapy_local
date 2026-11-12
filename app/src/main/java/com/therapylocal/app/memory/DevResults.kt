package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Debug harness output, `out/results.json`: per client and session the stored transcript (plain lines, plus `segments` with times and the
 * diarization speaker, and the current `speakerMap`), facts and mentions (with the segment they came from), and the answers to the
 * job's "queries" as the app's search gives them (closest windows and facts with cosine scores). Test data only: it holds client text.
 */
object DevResults {
    private const val WINDOWS = 5
    private const val FACTS = 10

    /** The session's current speaker map (raw id to role, how it was made, confidence, evidence); JSON null before the role job has run. */
    private fun speakerMap(store: MindStore, session: String): Any {
        val m = store.currentSpeakerMap(session) ?: return JSONObject.NULL
        val evidence: Any = runCatching { JSONTokener(m.evidenceJson).nextValue() }.getOrDefault(m.evidenceJson)
        return JSONObject().put("version", m.version).put("map", runCatching { JSONObject(m.mapJson) }.getOrDefault(JSONObject()))
            .put("source", m.source).put("confidence", m.confidence).put("evidence", evidence)
    }

    fun write(context: Context, out: File) {
        val store = MindStore.get(context)
        val job = File(MemoryFiles.devRoot(context), "job.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val sessions = JSONArray()
        for (client in store.clients()) for (s in store.sessions(client.id)) {
            val segments = store.segments(s.id)
            val transcript = JSONArray(segments.map { it.text })
            val segs = JSONArray(
                segments.map { JSONObject().put("idx", it.idx).put("startMs", it.startMs).put("endMs", it.endMs).put("speaker", it.speaker ?: JSONObject.NULL).put("text", it.text) },
            )
            sessions.put(
                JSONObject().put("client", client.id).put("session", s.id).put("transcript", transcript).put("segments", segs)
                    .put("speakerMap", speakerMap(store, s.id)),
            )
        }
        val facts = JSONArray()
        // Every stored fact, with who said it: the export shows what a roles swap would reveal. The app's own reads default to the patient's.
        for (client in store.clients()) for (f in store.facts(client.id, includeTherapist = true)) {
            val seen = store.factOccurrences(f.id, includeTherapist = true)
            facts.put(
                JSONObject().put("id", f.id).put("client", f.clientId).put("session", f.sessionId).put("heading", f.heading).put("content", f.content)
                    .put("evidence", f.evidence).put("segIdx", f.segIdx).put("segTo", f.segTo).put("startMs", f.startMs).put("endMs", f.endMs)
                    .put("timeMs", f.startMs).put("speaker", f.speaker ?: JSONObject.NULL).put("role", f.role?.json ?: JSONObject.NULL)
                    .put("scope", f.scope).put("category", f.category)
                    .put("mentionSessions", JSONArray(seen.filter { !it.first }.map { it.sessionId })),
            )
        }
        val answers = JSONArray()
        val queries = job.optJSONArray("queries")
        if (queries != null && queries.length() > 0) {
            val embedder = Engines.embedder(context)
            runBlocking { embedder.load() }
            for (i in 0 until queries.length()) {
                val q = queries.getJSONObject(i)
                val client = q.getString("client")
                val vec = runBlocking { embedder.embedText(q.getString("q"), true, LogCtx.NONE) }
                val ws = store.nearestWindows(client, vec, WINDOWS).map { (w, score) ->
                    JSONObject().put("session", w.sessionId).put("idx", w.idx).put("text", w.text).put("score", score.toDouble())
                }
                val fs = store.nearestFacts(client, vec, FACTS).map { (f, score) ->
                    JSONObject().put("id", f.id).put("session", f.sessionId).put("heading", f.heading).put("content", f.content).put("score", score.toDouble())
                }
                answers.put(JSONObject().put("client", client).put("q", q.getString("q")).put("windows", JSONArray(ws)).put("facts", JSONArray(fs)))
            }
        }
        out.resolve("results.json").writeText(
            JSONObject().put("embedModel", EmbeddingConfig.MODEL_ID).put("sessions", sessions).put("facts", facts).put("answers", answers).toString(),
        )
    }
}
