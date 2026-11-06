package com.therapylocal.app.models

import android.content.Context
import androidx.annotation.StringRes
import com.therapylocal.app.R

/** A language a client speaks in sessions. Canary is told it per recording. */
enum class SpeechLanguage(val code: String, @StringRes val labelRes: Int, val title: String) {
    English("en", R.string.speech_lang_english, "English"),
    Czech("cs", R.string.speech_lang_czech, "Čeština");

    fun displayName(context: Context): String = context.getString(labelRes)

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
