package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.config.ChunkConfig

/**
 * The audio of one recording as a single timeline, read from its saved chunks. Consecutive chunks share their overlap, so each chunk after the
 * first contributes only the audio after its own [ChunkRow.overlapMs]. Times are the chunks' own (milliseconds from the start of the recording),
 * the same as the segments use. Only the chunks a [slice] touches are loaded, one kept at a time, so a long recording is never held whole unless
 * the caller asks for all of it. Pure Kotlin; [load] is the only I/O.
 */
class ChunkAudio(private val rows: List<ChunkRow>, private val load: (idx: Int) -> AudioChunk) {
    private var cached: AudioChunk? = null
    private val sorted = rows.sortedBy { it.idx }
    private val firstIdx = sorted.firstOrNull()?.idx

    /** Where the recording ends. */
    val durationMs: Long = rows.maxOfOrNull { it.endMs } ?: 0L

    /** 16 kHz PCM16 of [startMs, endMs); a stretch no chunk covers is silence. */
    fun slice(startMs: Long, endMs: Long): ShortArray {
        val rate = ChunkConfig.SAMPLE_RATE / 1000
        val from = maxOf(startMs, 0L)
        val out = ShortArray(((maxOf(endMs, from) - from) * rate).toInt())
        for (row in sorted) {
            val own = if (row.idx == firstIdx) row.startMs else row.startMs + row.overlapMs
            val a = maxOf(from, own)
            val b = minOf(endMs, row.endMs)
            if (b <= a) continue
            val chunk = cached?.takeIf { it.idx == row.idx } ?: load(row.idx).also { cached = it }
            val src = ((a - row.startMs) * rate).toInt().coerceAtMost(chunk.pcm.size)
            val end = ((b - row.startMs) * rate).toInt().coerceAtMost(chunk.pcm.size)
            if (end > src) System.arraycopy(chunk.pcm, src, out, ((a - from) * rate).toInt(), end - src)
        }
        return out
    }
}
