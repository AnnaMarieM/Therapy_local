package com.therapylocal.app.facts

import kotlin.math.ceil

/**
 * How many tokens of context an LLM call needs, counted before the model is loaded: the prompt (instructions and the text in it), the
 * chat template and the reply all share the engine's context, so a context that is too small silently cuts the reply short.
 *
 * The tokenizer is not reachable from here, so tokens are estimated from the characters, on the safe side. Measured on the phone with Gemma 4:
 * Czech is about 2.7 characters per token (about 2 tokens per word, not the 1.4 of English), English about 4. Pure Kotlin.
 */
object PromptBudget {
    /** Used when the text has accented letters: below the measured 2.7 for Czech, so the estimate is high. */
    private const val CHARS_PER_TOKEN_ACCENTED = 2.4
    private const val CHARS_PER_TOKEN_PLAIN = 3.5

    /** Chat template and special tokens around the system and user text. */
    private const val TEMPLATE_TOKENS = 96

    /** On top of the estimated prompt: the estimate is only an estimate. */
    private const val SAFETY = 1.1

    /** The largest context the app asks an engine for. */
    const val MAX_CONTEXT = 16384
    private const val STEP = 512

    private fun accented(text: String): Boolean {
        var letters = 0
        var other = 0
        for (c in text) if (c.isLetter()) {
            letters++
            if (c.code > 127) other++
        }
        return letters > 0 && other * 100 >= letters
    }

    fun tokens(text: String): Int = ceil(text.length / (if (accented(text)) CHARS_PER_TOKEN_ACCENTED else CHARS_PER_TOKEN_PLAIN)).toInt()

    /** Tokens for [chars] characters of text with accented letters (Czech), for bounds where the text is not known yet. */
    fun accentedTokens(chars: Int): Int = ceil(chars / CHARS_PER_TOKEN_ACCENTED).toInt()

    /** Context one call needs: prompt, template, a safety margin, and the longest reply allowed. */
    fun required(system: String, user: String, maxOutputTokens: Int): Int =
        ceil((tokens(system) + tokens(user) + TEMPLATE_TOKENS) * SAFETY).toInt() + maxOutputTokens

    /** The context to load for [needed] tokens: rounded up, never below [floor] (the size the app always used) or above [MAX_CONTEXT]. */
    fun contextFor(needed: Int, floor: Int): Int = ((needed + STEP - 1) / STEP * STEP).coerceIn(floor, MAX_CONTEXT)
}
