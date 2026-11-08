package com.therapylocal.app.data

import com.therapylocal.app.config.ChunkConfig

/** A stretch of one speaker, in milliseconds from the start of the recording. */
data class TimedTurn(val startMs: Long, val endMs: Long, val speaker: String)

/**
 * Turns the diarizer's turns into the pieces of audio that are transcribed one by one: tidied (sorted, clamped to the recording, overlaps cut,
 * zero-length turns dropped), turns too short to transcribe absorbed by a neighbour, same-speaker turns with a tiny gap joined, and pieces
 * longer than the recogniser's chunk split into near-equal parts. Pure Kotlin.
 */
object TurnPlanner {
    /** A turn shorter than this is a click or a breath; the recogniser makes nothing of it. */
    const val MIN_TURN_MS = 400L

    /** The same speaker pausing for less than this has not stopped talking. */
    const val MERGE_GAP_MS = 300L

    /** No piece is longer than the recogniser's chunk. */
    const val MAX_PIECE_MS = ChunkConfig.CHUNK_MS

    /** How far around the even split point the cut-point chooser may look (in total, half on each side). */
    const val CUT_SEARCH_MS = 2_000L

    /**
     * [cut] gets a range and returns where to cut inside it; the caller can pass the quietest sample of the audio. The default is the middle.
     * The result is clamped into a range that keeps every part within [MAX_PIECE_MS].
     */
    fun plan(turns: List<TimedTurn>, durationMs: Long, cut: (startMs: Long, endMs: Long) -> Long = { s, e -> (s + e) / 2 }): List<TimedTurn> =
        split(joinSameSpeaker(absorbShort(tidy(turns, durationMs))), cut)

    private fun tidy(turns: List<TimedTurn>, durationMs: Long): List<TimedTurn> {
        val out = ArrayList<TimedTurn>()
        var lastEnd = 0L
        for (t in turns.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
            val s = maxOf(t.startMs, lastEnd)
            val e = minOf(t.endMs, durationMs)
            if (e <= s) continue
            out += TimedTurn(s, e, t.speaker)
            lastEnd = e
        }
        return out
    }

    /** A short turn extends the one before it, or, at the start, the one after it. Alone, it is dropped. */
    private fun absorbShort(turns: List<TimedTurn>): List<TimedTurn> {
        val out = ArrayList<TimedTurn>()
        var pendingStart = -1L
        for (t in turns) {
            val short = t.endMs - t.startMs < MIN_TURN_MS
            when {
                out.isNotEmpty() && short -> out[out.lastIndex] = out.last().copy(endMs = t.endMs)
                out.isEmpty() && short -> if (pendingStart < 0) pendingStart = t.startMs
                else -> {
                    out += if (pendingStart >= 0) t.copy(startMs = pendingStart) else t
                    pendingStart = -1L
                }
            }
        }
        return out
    }

    private fun joinSameSpeaker(turns: List<TimedTurn>): List<TimedTurn> {
        val out = ArrayList<TimedTurn>()
        for (t in turns) {
            val p = out.lastOrNull()
            if (p != null && p.speaker == t.speaker && t.startMs - p.endMs < MERGE_GAP_MS) out[out.lastIndex] = p.copy(endMs = t.endMs) else out += t
        }
        return out
    }

    private fun split(turns: List<TimedTurn>, cut: (Long, Long) -> Long): List<TimedTurn> {
        val out = ArrayList<TimedTurn>()
        for (t in turns) {
            var start = t.startMs
            while (t.endMs - start > MAX_PIECE_MS) {
                val rest = t.endMs - start
                val parts = (rest + MAX_PIECE_MS - 1) / MAX_PIECE_MS
                val even = start + rest / parts
                // The left part stays within MAX_PIECE_MS and what remains still fits into the parts left.
                val lo = maxOf(start + 1, t.endMs - (parts - 1) * MAX_PIECE_MS)
                val hi = start + MAX_PIECE_MS
                val at = cut(maxOf(lo, even - CUT_SEARCH_MS / 2), minOf(hi, even + CUT_SEARCH_MS / 2)).coerceIn(lo, hi)
                out += TimedTurn(start, at, t.speaker)
                start = at
            }
            out += TimedTurn(start, t.endMs, t.speaker)
        }
        return out
    }
}
