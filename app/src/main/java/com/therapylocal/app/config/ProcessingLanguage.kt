package com.therapylocal.app.config

import android.content.Context

/**
 * The language the pipeline works in (docs/AD/AD-17.md). [English]: the transcript is translated to English after transcription and every
 * later job reads English; only what is shown is translated back. [Native]: the client's own language is processed as it is spoken (the
 * behaviour from before AD-17), kept so one build can run the same sessions both ways.
 */
enum class ProcessingLanguage(val key: String) {
    English("english"),
    Native("native"),
    ;

    companion object {
        /** The default on this branch. */
        val DEFAULT = English

        /** The mode with [key]; anything else reads as the default. */
        fun of(key: String?): ProcessingLanguage = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The processing language, one for the whole vault, in the app's "settings" preferences (same shape as [SummaryFormatSetting]). */
object ProcessingLanguageSetting {
    const val PREF = "processing_language"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(context: Context): ProcessingLanguage = ProcessingLanguage.of(prefs(context).getString(PREF, null))

    fun set(context: Context, mode: ProcessingLanguage) {
        prefs(context).edit().putString(PREF, mode.key).apply()
    }
}
