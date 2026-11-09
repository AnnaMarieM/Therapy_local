package com.therapylocal.app.data

import com.therapylocal.app.config.ChunkConfig

/** Where to cut a long turn in two: the quietest 50 ms of the range, so the cut falls in a pause and not in a word. Pure Kotlin. */
object QuietCut {
    const val WINDOW_MS = 50L
    private const val HOP_MS = 10L

    /** The time in [startMs, endMs) at the middle of the window with the lowest energy (RMS); ties go to the one nearest the middle of the range. */
    fun at(pcm: ShortArray, startMs: Long, endMs: Long): Long {
        val middle = (startMs + endMs) / 2
        val rate = ChunkConfig.SAMPLE_RATE / 1000
        val window = (WINDOW_MS * rate).toInt()
        val from = (startMs * rate).coerceIn(0, pcm.size.toLong()).toInt()
        val to = (endMs * rate).coerceIn(0, pcm.size.toLong()).toInt()
        if (to - from < window) return middle
        var best = Long.MAX_VALUE
        var bestDistance = Long.MAX_VALUE
        var at = middle
        var s = from
        while (s + window <= to) {
            var e = 0L
            for (i in s until s + window) e += pcm[i].toLong() * pcm[i]
            val centre = (s + window / 2) / rate.toLong()
            val distance = kotlin.math.abs(centre - middle)
            if (e < best || (e == best && distance < bestDistance)) { best = e; bestDistance = distance; at = centre }
            s += (HOP_MS * rate).toInt()
        }
        return at
    }
}
