package com.therapylocal.app.memory

import com.therapylocal.app.data.SegmentRow

/**
 * Splits a transcript into windows that fit the model's context, each repeating the last overlap words of the previous one, and remembers
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

    /** Measured on the phone: 1,600 words of these transcripts were 2,237 Gemma tokens. */
    const val TOKENS_PER_WORD = 1.4

    /** Window sizes are given in (estimated) tokens: the model's tokenizer is not reachable from here, so words are converted at [TOKENS_PER_WORD]. */
    fun wordsFor(tokens: Int): Int = (tokens / TOKENS_PER_WORD).toInt()

    /** "Therapist: hello" lines become "hello": real transcripts from the recorder carry no speaker labels. */
    fun stripSpeakerLabels(text: String): String =
        text.lines().joinToString("\n") { it.replace(Regex("^\\s*\\**[A-Z][A-Za-z.]*\\**:\\**\\s*"), "") }
}
