package com.therapylocal.app.workflow

import com.therapylocal.app.jobs.Llm
import com.therapylocal.app.translate.Pivot

/** What was heard in one turn: the words in the language spoken, and their English translation (null when there was none or the answer had none). */
data class TurnText(val source: String, val english: String?)

/**
 * The Fast workflow's listening step (docs/AD/AD-22.md): Gemma hears one turn (at most 30 s) and answers in one go with the transcript in the language
 * spoken and its English translation. The reply format is two labelled lines; [parse] is pure Kotlin so a JVM test pins it. A reply that does not
 * follow the format is asked for once more; after that its text is kept as the transcript with no translation, and the TRANSLATE job fills the gap.
 */
object TurnListener {
    /** Part of the stored translation's prompt version: a changed prompt makes the stored English stale. */
    const val VERSION = "listen-1"
    const val MAX_ATTEMPTS = 2
    const val MAX_OUTPUT_TOKENS = 600
    const val TEMPERATURE = 0.0
    const val TOP_K = 1

    fun system(clientLanguage: String): String = if (clientLanguage.equals(Pivot.EN, true)) {
        "You are a careful transcriber of a psychotherapy session recorded in English. Transcribe exactly what is said, word for word, " +
            "including hesitations that carry meaning. Never add, summarise or correct. Keep every negation, number, name and place exactly as spoken. " +
            "If a passage cannot be understood write [unclear] instead of guessing."
    } else {
        val name = Pivot.languageName(clientLanguage)
        "You are a careful transcriber and translator of a psychotherapy session recorded in $name. First transcribe exactly what is said, word for word, in $name. " +
            "Then translate that transcript into English. Never add, summarise or correct. Keep every negation, number, name and place exactly as spoken. " +
            "If a passage cannot be understood write [unclear] instead of guessing."
    }

    fun user(clientLanguage: String): String = if (clientLanguage.equals(Pivot.EN, true)) {
        "Transcribe this audio. Answer with the transcript only."
    } else {
        val name = Pivot.languageName(clientLanguage).uppercase()
        "Listen to this audio. Answer in exactly this format and nothing else:\n$name: <the verbatim transcript>\nENGLISH: <the English translation>"
    }

    /** The reply as a [TurnText], or null when it does not have the labels asked for (a translating call) or is empty. */
    fun parse(reply: String, clientLanguage: String): TurnText? {
        val text = reply.trim()
        if (text.isEmpty()) return null
        if (clientLanguage.equals(Pivot.EN, true)) return TurnText(text.removePrefix("TRANSCRIPT:").trim().ifEmpty { return null }, null)
        val label = Pivot.languageName(clientLanguage)
        val m = Regex("(?is)^\\s*(?:\\*\\*)?${Regex.escape(label)}\\s*(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.*?)\\s*(?:\\*\\*)?\\s*ENGLISH\\s*(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.*)$").find(text) ?: return null
        val source = m.groupValues[1].trim()
        val english = m.groupValues[2].trim()
        if (source.isEmpty() || english.isEmpty()) return null
        return TurnText(source, english)
    }

    /** What is kept of a reply that never followed the format: the text without the labels, no translation. */
    fun lenient(reply: String, clientLanguage: String): TurnText {
        val label = Pivot.languageName(clientLanguage)
        val t = reply.trim().replace(Regex("(?i)^\\s*(?:\\*\\*)?(?:$label|TRANSCRIPT)(?:\\*\\*)?\\s*:\\s*"), "").trim()
        return TurnText(t, null)
    }

    /** One turn through [llm] (which must hear): up to [MAX_ATTEMPTS] tries; a call that throws is not retried here (an engine death or a stop must propagate). */
    fun listen(llm: Llm, pcm: ShortArray, clientLanguage: String, attempts: (Int) -> Unit = {}): TurnText {
        var last = ""
        for (i in 1..MAX_ATTEMPTS) {
            attempts(i)
            last = llm.listen(system(clientLanguage), user(clientLanguage), pcm, MAX_OUTPUT_TOKENS, TEMPERATURE, TOP_K)
            parse(last, clientLanguage)?.let { return it }
        }
        return lenient(last, clientLanguage)
    }
}
