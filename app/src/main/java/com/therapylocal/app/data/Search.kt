package com.therapylocal.app.data

import android.os.SystemClock
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.SearchConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.util.PriorityQueue

/** One chunk hit. [score] compares the query with the chunk's AUDIO vector; [altScore] with its transcript's text vector (for comparison only). */
data class Hit(val row: ChunkRow, val score: Float, val altScore: Float?)

/** Adjacent (so time-overlapping) chunks of one recording collapsed into a single result. */
data class HitGroup(val recordingId: Long, val hits: List<Hit>) {
    val score: Float get() = hits.maxOf { it.score }
    val startMs: Long get() = hits.minOf { it.row.startMs }
    val endMs: Long get() = hits.maxOf { it.row.endMs }
    val text: String get() = TranscriptMerge.merge(hits.sortedBy { it.row.idx }.map { TranscriptClean.stripPromptEcho(it.row.text) }).text
}

object Search {
    const val TAG = "Search"

    /** Brute-force cosine (dot product, vectors are normalised) over every stored chunk of the current embedding model. */
    fun topK(store: Store, query: FloatArray, k: Int, kind: String, ctx: LogCtx): List<Hit> =
        AppLog.span(TAG, "search_scan", ctx, "kind" to kind, "k" to k, "dim" to query.size) { span ->
            val heap = PriorityQueue<Hit>(k + 1, compareBy { it.score })
            var rows = 0
            val start = SystemClock.elapsedRealtimeNanos()
            store.forEachEmbedding(EmbeddingConfig.MODEL_ID) { row, audio, text ->
                rows++
                if (audio.size == query.size) {
                    val alt = if (text != null && text.size == query.size) Embedder.dot(query, text) else null
                    heap.add(Hit(row, Embedder.dot(query, audio), alt))
                    if (heap.size > k) heap.poll()
                }
            }
            val scanMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val hits = heap.sortedByDescending { it.score }
            span.put("rows_scanned", rows)
            span.put("skipped_other_model", store.countOtherModels(EmbeddingConfig.MODEL_ID))
            span.put("scan_ms", "%.2f".format(scanMs))
            span.put("hits", hits.size)
            // ids and scores only, never text. top = audio-vector ranking, alt = transcript-text-vector score of the same chunk
            span.put("top", hits.joinToString(",") { "${it.row.recordingId}.${it.row.idx}:${"%.3f".format(it.score)}" })
            span.put("alt", hits.joinToString(",") { "${it.row.recordingId}.${it.row.idx}:${it.altScore?.let { a -> "%.3f".format(a) } ?: "-"}" })
            hits
        }

    /**
     * Collapses neighbours: chunks i and i+1 of the same recording overlap in time, so showing both would show the same audio twice.
     * Runs of consecutive indices become one group; groups are ranked by their best chunk.
     */
    fun collapse(hits: List<Hit>, ctx: LogCtx): List<HitGroup> {
        if (hits.isEmpty()) return emptyList()
        // Only chunks close to the best score count as relevant; weak neighbours must not be merged into a strong hit.
        val best = hits.maxOf { it.score }
        val relevant = hits.filter { it.score >= best - SearchConfig.SCORE_MARGIN }
        val groups = ArrayList<HitGroup>()
        for ((rid, list) in relevant.groupBy { it.row.recordingId }) {
            val sorted = list.sortedBy { it.row.idx }
            var run = ArrayList<Hit>()
            for (h in sorted) {
                if (run.isNotEmpty() && h.row.idx - run.last().row.idx > 1) {
                    groups += HitGroup(rid, run)
                    run = ArrayList()
                }
                run += h
            }
            if (run.isNotEmpty()) groups += HitGroup(rid, run)
        }
        val out = groups.sortedByDescending { it.score }
        AppLog.i(
            TAG, "search_collapse", ctx,
            "raw_hits" to hits.size, "relevant" to relevant.size, "margin" to SearchConfig.SCORE_MARGIN, "groups" to out.size, "merged" to (relevant.size - out.size),
        )
        return out
    }
}
