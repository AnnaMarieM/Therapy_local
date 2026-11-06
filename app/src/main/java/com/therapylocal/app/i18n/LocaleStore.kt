package com.therapylocal.app.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language. Android 13+ keeps it in the system (so it also shows in the phone's per-app language settings);
 * on Android 12 it is kept in the "settings" prefs and applied by wrapping every context ([wrap]).
 * Until a language is chosen nothing is stored and the phone's own language applies, falling back to English.
 */
object LocaleStore {
    private const val PREFS = "settings"
    private const val KEY = "app_language"

    /** The tag the user chose, or null while the phone's language still applies. */
    fun chosen(ctx: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        } else {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }

    /** The language the UI is showing: the chosen one, else the phone's if supported, else English. */
    fun current(ctx: Context): AppLanguage = Languages.match(chosen(ctx) ?: Locale.getDefault().language)

    /** The language to preselect on the first screen: the phone's, if supported. */
    fun detected(): AppLanguage = Languages.match(Locale.getDefault().language)

    private fun set(ctx: Context, tag: String) {
        val language = Languages.byTag(tag) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
        }
    }

    /** Set when the language was switched, until the main screen has offered to read the sessions again in it. */
    private const val KEY_OFFER = "offer_reprocess"

    /** True once after a language switch; the activity is recreated by it, so the answer outlives the screen that asked. */
    fun takeReprocessOffer(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OFFER, false).also { if (it) prefs.edit().remove(KEY_OFFER).apply() }
    }

    /** Switches the app to [tag] now: Android 13+ recreates the activity itself, Android 12 needs it done here. */
    fun choose(activity: Activity, tag: String) {
        if (Languages.byTag(tag) != null && !tag.equals(current(activity).tag, ignoreCase = true)) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFFER, true).apply()
        }
        set(activity, tag)
        if (Build.VERSION.SDK_INT < 33) activity.recreate()
    }

    /** For Application, Activity and Service attachBaseContext. A no-op on Android 13+, where the system applies the locale. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
