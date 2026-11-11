package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.TranscriptFormat

/**
 * Splits a transcript into windows ([byTurns] for the extraction; [bySegments] cuts plain word windows) that fit the model's context, each repeating the last overlap words of the previous one, and remembers
 * which segments each window spans ([Span.segFrom]..[Span.segTo]: the segments of its first and last word). Pure Kotlin.
 */
object TranscriptWindows {
    data class Span(val segFrom: Int, val segTo: Int, val text: String)

    /** No window for a transcript without words. */
    fun bySegments(segments: List<SegmentRow>, windowWords: Int, overlapWords: Int): List<Span> {
        require(windowWords > overlapWords && overlapWords >= 0) { "windowWords must exceed overlapWords" }
        val words = ArrayList<String>()
        val segOf = ArrayList<Int>()
        for (s in segments) for (w in s.text.split(Regex("\\s+"))) if (w.isNotEmpty()) {
            words += w
            segOf += s.idx
        }
        val out = ArrayList<Span>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + windowWords, words.size)
            out += Span(segOf[start], segOf[end - 1], words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += windowWords - overlapWords
        }
        return out
    }

    /**
     * The extraction windows: the transcript as a labelled dialogue ([TranscriptFormat]: a `[hh:mm:ss]` line, then `Label: text`, blocks
     * separated by a blank line), cut on turn boundaries so that no window starts or ends inside someone's turn, unless one turn alone is
     * longer than [windowWords] (then it is cut by words and every piece repeats the turn's header). A window after the first begins with the
     * turns before its first new one, up to [overlapWords] words, so a patient's answer comes with the therapist's question. Each turn counts its
     * header words. [Span.segFrom]..[Span.segTo] are the segments of its first and last turn. Utterances without text are left out. [label] is
     * what [TranscriptFormat.format] takes, e.g. the role labels of the current speaker map. No window for a transcript without words.
     */
    fun byTurns(segments: List<SegmentRow>, windowWords: Int, overlapWords: Int, label: (speaker: String?) -> String): List<Span> {
        require(windowWords > overlapWords && overlapWords >= 0) { "windowWords must exceed overlapWords" }
        class Turn(val seg: Int, val text: String) {
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        }
        val turns = ArrayList<Turn>()
        for (s in segments) {
            if (s.text.isBlank()) continue
            val block = TranscriptFormat.format(listOf(s), label)
            val header = block.substring(0, block.indexOf(": ", block.indexOf('\n')) + 2)
            val words = block.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val room = windowWords - header.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words.size <= windowWords || room < 1) {
                turns += Turn(s.idx, block)
            } else {
                // A turn longer than a window: cut by words, each piece with the turn's header.
                val body = s.text.trim().split(Regex("\\s+"))
                body.chunked(room).forEach { turns += Turn(s.idx, header + it.joinToString(" ")) }
            }
        }
        val out = ArrayList<Span>()
        var from = 0
        while (from < turns.size) {
            // Context first: the turns just before the first new one, as many as fit in the overlap, but never so many that the first new turn
            // does not fit beside them. The new turns then fill what is left of the window.
            var ctx = from
            var ctxWords = 0
            while (ctx > 0 && ctxWords + turns[ctx - 1].words <= overlapWords && ctxWords + turns[ctx - 1].words + turns[from].words <= windowWords) ctxWords += turns[--ctx].words
            var end = from
            var count = ctxWords
            while (end < turns.size && (end == from || count + turns[end].words <= windowWords)) count += turns[end++].words
            out += Span(turns[ctx].seg, turns[end - 1].seg, turns.subList(ctx, end).joinToString("\n\n") { it.text })
            from = end
        }
        return out
    }

    /** Measured on the phone: 1,600 words of these transcripts were 2,237 Gemma tokens. */
    const val TOKENS_PER_WORD = 1.4

    /** Window sizes are given in (estimated) tokens: the model's tokenizer is not reachable from here, so words are converted at [TOKENS_PER_WORD]. */
    fun wordsFor(tokens: Int): Int = (tokens / TOKENS_PER_WORD).toInt()

    /**
     * "Therapist: hello" lines become "hello". Stored utterances carry their speaker in a column, not in the text; extraction reads the labelled
     * dialogue built from the column ([byTurns]) and never calls this. It only cleans hand-written transcripts with labels in the text when the
     * developer harness imports them.
     */
    fun stripSpeakerLabels(text: String): String =
        text.lines().joinToString("\n") { it.replace(Regex("^\\s*\\**[A-Z][A-Za-z.]*\\**:\\**\\s*"), "") }
}
