package com.therapylocal.app.ui.logic

import com.therapylocal.app.ui.models.FactMention
import com.therapylocal.app.ui.models.TranscriptLine

/** How well a mention could be placed in the transcript as it is now. */
enum class TargetState {
    /** The stored segments are there and carry the stored times: the lines are highlighted. */
    Exact,

    /** The segments are gone or are not the ones the fact was made from (a newer transcript version): shown at the same time, nothing highlighted. */
    Moved,

    /** Nothing to place it by: the transcript is shown from the top. */
    Missing,
}

/**
 * Where tapping a fact leads: the transcript of [sessionId], scrolled to segment [scrollTo] (null: stay at the top), with the segments in
 * [highlight] marked (empty: none). Pure data, see [FactJump].
 */
data class TranscriptTarget(val sessionId: String, val scrollTo: Int?, val highlight: IntRange, val state: TargetState) {
    fun highlights(idx: Int) = idx in highlight
}

/**
 * The navigation from a fact to its line (AD-14: a fact stores its segment range and times). No Android here, so it is tested on the JVM.
 *
 * A fact has one or more mentions: its first occurrence and the later sessions it came up in again. Tapping the fact opens [initial], the first
 * occurrence; the others are listed in the layer and [resolve]d when picked.
 */
object FactJump {
    /** The mention a tap on the fact opens: its first occurrence, else (the fact's own session was removed and a mention took over) the earliest listed. */
    fun initial(mentions: List<FactMention>): FactMention? = mentions.firstOrNull { it.first } ?: mentions.firstOrNull()

    /**
     * [mention] placed in [lines], the session's current transcript. The stored range is trusted when its first segment exists and, if times
     * were stored, starts when the fact says (the transcript may have been replaced by a newer version with other segments under the same
     * numbers). A range that runs over a turn change is highlighted whole; a last segment that is gone is cut to the last one there is. When the
     * range does not hold, the segment at the stored time is scrolled to without a highlight; with no time either, there is nothing to go by.
     */
    fun resolve(mention: FactMention, lines: List<TranscriptLine>): TranscriptTarget {
        val from = mention.segFrom
        val to = maxOf(mention.segTo, from)
        val first = lines.firstOrNull { it.idx == from }
        val timed = mention.endMs > 0
        if (first != null && (!timed || first.startMs == mention.startMs)) {
            val last = lines.lastOrNull { it.idx in from..to } ?: first
            return TranscriptTarget(mention.sessionId, from, from..last.idx, TargetState.Exact)
        }
        val atTime = if (timed) lines.firstOrNull { mention.startMs >= it.startMs && mention.startMs < it.endMs } else null
        return if (atTime != null) TranscriptTarget(mention.sessionId, atTime.idx, IntRange.EMPTY, TargetState.Moved)
        else TranscriptTarget(mention.sessionId, null, IntRange.EMPTY, TargetState.Missing)
    }

    /** The row of the list to scroll to for [target], after [headerItems] rows above the lines; null when there is nothing to scroll to. */
    fun listIndex(target: TranscriptTarget, lines: List<TranscriptLine>, headerItems: Int): Int? {
        val at = target.scrollTo?.let { idx -> lines.indexOfFirst { it.idx == idx } } ?: return null
        return if (at < 0) null else headerItems + at
    }
}
