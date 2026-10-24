package com.therapylocal.app.memory

/** Splits a long transcript into windows that fit the model's context, each repeating the last [overlapWords] words of the previous one. Pure Kotlin. */
object TranscriptWindows {
    fun split(text: String, windowWords: Int, overlapWords: Int): List<String> {
        require(windowWords > overlapWords && overlapWords >= 0) { "windowWords must exceed overlapWords" }
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= windowWords) return listOf(words.joinToString(" "))
        val out = ArrayList<String>()
        val step = windowWords - overlapWords
        var start = 0
        while (start < words.size) {
            val end = minOf(start + windowWords, words.size)
            out += words.subList(start, end).joinToString(" ")
            if (end == words.size) break
            start += step
        }
        return out
    }

    /** Measured on the phone: 1,600 words of these transcripts were 2,237 Gemma tokens. */
    const val TOKENS_PER_WORD = 1.4

    /** Chunks sized in (estimated) tokens: the model's tokenizer is not reachable from here, so words are converted at [TOKENS_PER_WORD]. */
    fun splitByTokens(text: String, chunkTokens: Int, overlapTokens: Int): List<String> =
        split(text, (chunkTokens / TOKENS_PER_WORD).toInt(), (overlapTokens / TOKENS_PER_WORD).toInt())

    /** "Therapist: hello" lines become "hello": real transcripts from the recorder carry no speaker labels. */
    fun stripSpeakerLabels(text: String): String =
        text.lines().joinToString("\n") { it.replace(Regex("^\\s*\\**[A-Z][A-Za-z.]*\\**:\\**\\s*"), "") }
}
