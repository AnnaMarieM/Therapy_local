package com.therapylocal.app.facts

/**
 * Which language the extraction and review prompts are in. Czech prompts are used only when the app is in Czech and the client speaks
 * Czech: with the English prompts Gemma wrote the facts of a Czech session in English (measured), and with the Czech ones it writes them in Czech.
 * Pure Kotlin.
 */
object PromptLanguage {
    const val EN = "en"
    const val CS = "cs"

    /** Folder of the Czech prompt files next to the English ones (see [Prompts.named]). */
    const val CS_DIR = "cs"

    /**
     * [appLanguage] is the UI language tag and [clientLanguage] the language code the client speaks in sessions (see
     * [com.therapylocal.app.models.SpeechLanguage]): Czech prompts only if both are Czech.
     */
    fun choose(appLanguage: String, clientLanguage: String): String =
        if (appLanguage.equals(CS, ignoreCase = true) && clientLanguage.equals(CS, ignoreCase = true)) CS else EN
}
