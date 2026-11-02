package com.therapylocal.app.models

import android.content.Context

/** A language a client speaks in sessions. Speech recognition is chosen per client from this. */
enum class Language(val code: String, val title: String) {
    English("en", "English"),
    Czech("cs", "Čeština");

    companion object {
        fun of(code: String?): Language = entries.firstOrNull { it.code == code } ?: English
    }
}

/** The default language chosen at first run, and the languages in use, which decide the Whisper builds to download. */
object Languages {
    private const val DEFAULT = "default_language"
    private const val IN_USE = "client_languages"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun default(context: Context): Language = Language.of(prefs(context).getString(DEFAULT, null))

    /** Sets the default for new clients and makes sure its Whisper build is downloaded. */
    fun setDefault(context: Context, language: Language) {
        if (language == default(context)) return
        prefs(context).edit().putString(DEFAULT, language.code).apply()
        ModelSetup.retry(context)
    }

    /** Every language the default and the clients use. */
    fun inUse(context: Context): Set<Language> =
        (prefs(context).getStringSet(IN_USE, emptySet()).orEmpty().map(Language::of) + default(context)).toSet()

    /** Records that a client uses [language]; its Whisper build is downloaded if missing. */
    fun markInUse(context: Context, language: Language) {
        val p = prefs(context)
        val now = p.getStringSet(IN_USE, emptySet()).orEmpty()
        if (language.code in now) return
        p.edit().putStringSet(IN_USE, now + language.code).apply()
        ModelSetup.retry(context)
    }
}
