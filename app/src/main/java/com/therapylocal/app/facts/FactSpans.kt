package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow

/** Where a fact was said: transcript segments [segFrom]..[segTo], their time range, and the raw diarization id of the first one (null: not diarized). */
data class FactSpan(val segFrom: Int, val segTo: Int, val startMs: Long, val endMs: Long, val speaker: String?)

/** Turns the segment range of a fact into what is stored with it. Pure. */
object FactSpans {
    /**
     * The span of segments [segFrom]..[segTo] of [segments]: start of the first, end of the last, and the speaker of the first (a quote that
     * runs over a turn change belongs to the speaker who started it). Null when [segFrom] is not in [segments].
     */
    fun of(segFrom: Int, segTo: Int, segments: List<SegmentRow>): FactSpan? {
        val first = segments.firstOrNull { it.idx == segFrom } ?: return null
        val last = segments.firstOrNull { it.idx == maxOf(segTo, segFrom) } ?: first
        return FactSpan(first.idx, last.idx, first.startMs, maxOf(last.endMs, first.endMs), first.speaker)
    }
}
