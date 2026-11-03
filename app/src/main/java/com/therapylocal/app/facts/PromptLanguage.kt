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
     * [appLanguage] is the UI language tag, [clientSpeech] the speech language setting of the client ("auto", "en", "sk", "cs", ...), and
     * [transcript] a sample of the session's text, read only when the setting is "auto".
     */
    fun choose(appLanguage: String, clientSpeech: String, transcript: String): String {
        if (!appLanguage.equals(CS, ignoreCase = true)) return EN
        return when (clientSpeech.lowercase()) {
            CS -> CS
            "auto", "" -> if (looksCzech(transcript)) CS else EN
            else -> EN
        }
    }

    /**
     * Czech by its own letters: ě, ř and ů make about 3 of every 100 letters of Czech text and none of Slovak, Polish or English, so a Slovak
     * client of a Czech-language app keeps the English prompts. Needs enough text to say.
     */
    fun looksCzech(text: String): Boolean {
        var letters = 0
        var czech = 0
        for (c in text) if (c.isLetter()) {
            letters++
            if (c == 'ě' || c == 'ř' || c == 'ů' || c == 'Ě' || c == 'Ř' || c == 'Ů') czech++
        }
        return letters >= MIN_LETTERS && czech * 1000 >= letters * MIN_PER_MILLE
    }

    private const val MIN_LETTERS = 200
    private const val MIN_PER_MILLE = 15
}
