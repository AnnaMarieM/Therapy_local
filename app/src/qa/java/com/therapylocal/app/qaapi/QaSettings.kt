package com.therapylocal.app.qaapi

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.DisplayConfig
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.config.SummaryFormatSetting
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.config.saveLiveCueSettings
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.i18n.Languages
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.SpeechLanguages
import com.therapylocal.app.ui.data.SessionSummaryRepository
import org.json.JSONArray
import org.json.JSONObject

enum class QaSettingType(val json: String) { ENUM("enum"), BOOL("bool"), INT("int"), FLOAT("float") }

/**
 * One app setting as the QA API sees it. [read] and [apply] go to the app's own getter and setter. Values are typed: a String for an enum,
 * a Boolean, a Long for an int, a Double for a float. [values] lists an enum's ids (a bool has true and false); [min] and [max] bound a number.
 */
class QaSetting(
    val key: String,
    val type: QaSettingType,
    val default: Any,
    val description: String,
    val values: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val read: () -> Any,
    val apply: (Any) -> Unit,
) {
    fun describe(): JSONObject = JSONObject().put("key", key).put("type", type.json).apply {
        when (type) {
            QaSettingType.ENUM -> put("values", JSONArray(values))
            QaSettingType.BOOL -> put("values", JSONArray(listOf(true, false)))
            QaSettingType.INT -> { put("min", min!!.toLong()); put("max", max!!.toLong()) }
            QaSettingType.FLOAT -> { put("min", min!!); put("max", max!!) }
        }
        put("default", default).put("value", read()).put("description", description)
    }

    /** The allowed values or range, for the error message. */
    fun allowed(): String = when (type) {
        QaSettingType.ENUM -> values.joinToString(", ")
        QaSettingType.BOOL -> "true, false"
        QaSettingType.INT -> "an integer from ${min!!.toLong()} to ${max!!.toLong()}"
        QaSettingType.FLOAT -> "a number from $min to $max"
    }

    /** [raw] as the typed value, or bad_request listing what is allowed. Nothing is coerced or clamped. */
    fun parse(raw: String): Any {
        val s = raw.trim()
        val bad = QaException(QaCode.BAD_REQUEST, "bad value for $key; allowed: ${allowed()}")
        return when (type) {
            QaSettingType.ENUM -> values.firstOrNull { it == s } ?: throw bad
            QaSettingType.BOOL -> when (s) { "true" -> true; "false" -> false; else -> throw bad }
            QaSettingType.INT -> s.toLongOrNull()?.takeIf { it >= min!! && it <= max!! } ?: throw bad
            QaSettingType.FLOAT -> s.toFloatOrNull()?.takeIf { it.isFinite() && it >= min!!.toFloat() && it <= max!!.toFloat() }?.let { it.toString().toDouble() } ?: throw bad
        }
    }
}

/** `get_settings` and `set_setting` over a list of [QaSetting]. */
object QaSettings {
    fun get(settings: List<QaSetting>): JSONObject = JSONObject().put("settings", JSONArray(settings.map { it.describe() }))

    fun set(settings: List<QaSetting>, key: String, raw: String): JSONObject {
        val s = settings.firstOrNull { it.key == key } ?: throw QaException(QaCode.BAD_REQUEST, "unknown setting; known: ${settings.joinToString(", ") { it.key }}")
        val value = s.parse(raw)
        val old = s.read()
        s.apply(value)
        val new = s.read()
        return JSONObject().put("setting", s.key).put("old", old).put("new", new).put("changed", old != new)
    }
}

/** The app's settings, built from the app's own constants, ranges and setters so the list cannot drift from it. */
fun appSettings(app: Context): List<QaSetting> {
    // Belt and braces: the setters below change real prefs, the model choice, the locale; never build them outside the QA build.
    if (!QaGuard.isQaApplicationId(app.packageName)) throw QaException(QaCode.NOT_QA_BUILD, "this is not the QA build")
    val prefs = { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    fun cues() = prefs().liveCueSettings()
    fun saveCues(s: LiveCueSettings) { prefs().saveLiveCueSettings(s); Engines.onLiveCueSettingsChanged() }
    fun f(x: Float) = x.toString().toDouble()
    return listOf(
        QaSetting("app_language", QaSettingType.ENUM, Languages.FALLBACK, "Language of the app's screens (the phone's language applies until one is chosen; this chooses one).",
            values = Languages.all.map { it.tag }, read = { LocaleStore.current(app).tag },
            apply = { v ->
                // LocaleStore.set is private and choose() needs an Activity; same storage as LocaleStore.set: the system on 13+, the prefs below.
                val tag = v as String
                if (Build.VERSION.SDK_INT >= 33) app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
                else prefs().edit().putString("app_language", tag).apply()
            }),
        QaSetting("default_language", QaSettingType.ENUM, SpeechLanguage.English.code, "Spoken language preselected for new clients.",
            values = SpeechLanguage.entries.map { it.code }, read = { SpeechLanguages.default(app).code },
            apply = { SpeechLanguages.setDefault(app, SpeechLanguage.of(it as String)) }),
        QaSetting("summary_format", QaSettingType.ENUM, SummaryConfig.DEFAULT_FORMAT_ID, "Vault-wide summary format; each client's last summary is rebuilt.",
            values = SummaryConfig.FORMATS.keys.toList(), read = { SummaryFormatSetting.get(app) },
            apply = { SessionSummaryRepository.create(app).changeFormat(it as String) }),
        QaSetting("gemma_variant", QaSettingType.ENUM, GemmaVariant.E2B.key, "Gemma build of the memory LLM (the file is downloaded if missing). E4B needs about 11 GiB of RAM.",
            values = GemmaVariant.entries.map { it.key }, read = { ModelSetup.gemmaVariant(app).key },
            apply = { v -> ModelSetup.selectGemma(app, GemmaVariant.entries.first { it.key == v }) }),
        QaSetting("backend_override", QaSettingType.ENUM, "auto", "Which accelerator the engines try first; auto is the normal ladder.",
            values = listOf("auto") + Accelerators.all().map { it.id.lowercase() }, read = { prefs().getString("backend_override", "auto")!! },
            apply = { v -> prefs().edit().putString("backend_override", v as String).apply(); BackendPolicy.override = v }),
        QaSetting("keep_screen_awake", QaSettingType.BOOL, true, "Keep the screen on while the app is in front.",
            read = { prefs().getBoolean(DisplayConfig.PREF_KEEP_SCREEN_AWAKE, true) },
            apply = { DisplayConfig.setKeepScreenAwake(app, it as Boolean) }),
        QaThermal.setting(read = { prefs().getBoolean(QaThermal.SETTING_KEY, true) },
            apply = { prefs().edit().putBoolean(QaThermal.SETTING_KEY, it).apply(); QaThermalSampler.kick() }),
        QaThermal.setting(read = { prefs().getBoolean(QaThermal.SETTING_KEY, true) },
            apply = { prefs().edit().putBoolean(QaThermal.SETTING_KEY, it).apply(); QaThermalSampler.kick() }),
        QaSetting("live_cue_interval_ms",QaSettingType.INT, LiveCueSettings.DEFAULT_INTERVAL_MS, "Live cues: pause between ticks (ms).",
            min = LiveCueSettings.INTERVAL_MS.first.toDouble(), max = LiveCueSettings.INTERVAL_MS.last.toDouble(), read = { cues().intervalMs },
            apply = { v -> val c = cues(); saveCues(LiveCueSettings.of(v as Long, c.lookbackS, c.smoothing, c.scoreFloor)) }),
        QaSetting("live_cue_lookback_s", QaSettingType.INT, LiveCueSettings.DEFAULT_LOOKBACK_S.toLong(), "Live cues: seconds of audio embedded per tick.",
            min = LiveCueSettings.LOOKBACK_S.first.toDouble(), max = LiveCueSettings.LOOKBACK_S.last.toDouble(), read = { cues().lookbackS.toLong() },
            apply = { v -> val c = cues(); saveCues(LiveCueSettings.of(c.intervalMs, (v as Long).toInt(), c.smoothing, c.scoreFloor)) }),
        QaSetting("live_cue_smoothing", QaSettingType.FLOAT, f(LiveCueSettings.DEFAULT_SMOOTHING), "Live cues: weight of the newest tick in a fact's score (1 = no smoothing).",
            min = f(LiveCueSettings.SMOOTHING.start), max = f(LiveCueSettings.SMOOTHING.endInclusive), read = { f(cues().smoothing) },
            apply = { v -> val c = cues(); saveCues(LiveCueSettings.of(c.intervalMs, c.lookbackS, (v as Double).toFloat(), c.scoreFloor)) }),
        QaSetting("live_cue_score_floor", QaSettingType.FLOAT, f(LiveCueSettings.DEFAULT_SCORE_FLOOR), "Live cues: smoothed cosine a fact needs to be shown.",
            min = f(LiveCueSettings.SCORE_FLOOR.start), max = f(LiveCueSettings.SCORE_FLOOR.endInclusive), read = { f(cues().scoreFloor) },
            apply = { v -> val c = cues(); saveCues(LiveCueSettings.of(c.intervalMs, c.lookbackS, c.smoothing, (v as Double).toFloat())) }),
    )
}
