package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug harness output, `out/results.json`: per client and session the stored transcript, facts and mentions, and the answers to the
 * job's "queries" as the app's search gives them (closest windows and facts with cosine scores). Test data only: it holds client text.
 */
object DevResults {
    private const val WINDOWS = 5
    private const val FACTS = 10

    fun write(context: Context, out: File) {
        val store = MindStore.get(context)
        val job = File(MemoryFiles.devRoot(context), "job.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val sessions = JSONArray()
        for (client in store.clients()) for (s in store.sessions(client.id)) {
            val transcript = JSONArray(store.segments(s.id).map { it.text })
            sessions.put(JSONObject().put("client", client.id).put("session", s.id).put("transcript", transcript))
        }
        val facts = JSONArray()
        for (client in store.clients()) for (f in store.facts(client.id)) {
            val seen = store.factOccurrences(f.id)
            facts.put(
                JSONObject().put("id", f.id).put("client", f.clientId).put("session", f.sessionId).put("heading", f.heading).put("content", f.content)
                    .put("evidence", f.evidence).put("mentionSessions", JSONArray(seen.filter { !it.first }.map { it.sessionId })),
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
