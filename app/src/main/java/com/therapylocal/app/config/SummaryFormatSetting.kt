package com.therapylocal.app.config

import android.content.Context

/**
 * The summary format the therapist chose, one for the whole vault, kept in the app's "settings" preferences like the other options. Only an
 * id of [SummaryConfig.FORMATS] is stored; anything else reads as the default. Changing it is done through
 * [com.therapylocal.app.ui.data.SessionSummaryRepository.changeFormat], which also queues the rebuild.
 */
object SummaryFormatSetting {
    const val PREF = "summary_format"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(context: Context): String = SummaryConfig.format(prefs(context).getString(PREF, null)).id

    fun set(context: Context, id: String) {
        prefs(context).edit().putString(PREF, SummaryConfig.format(id).id).apply()
    }
}
