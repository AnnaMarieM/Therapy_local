package com.therapylocal.app.models

import android.content.Context

/** A language a client speaks in sessions. Canary is told it per recording. */
enum class SpeechLanguage(val code: String, val title: String) {
    English("en", "English"),
    Czech("cs", "Čeština");

    companion object {
        fun of(code: String?): SpeechLanguage = entries.firstOrNull { it.code == code } ?: English
    }
}

/** The default language chosen at first run, for new clients. */
object SpeechLanguages {
    private const val DEFAULT = "default_language"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun default(context: Context): SpeechLanguage = SpeechLanguage.of(prefs(context).getString(DEFAULT, null))

    /** Sets the default for new clients. */
    fun setDefault(context: Context, language: SpeechLanguage) {
        if (language == default(context)) return
        prefs(context).edit().putString(DEFAULT, language.code).apply()
    }
}
