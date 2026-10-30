package com.therapylocal.app.perf

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One model call as the engine measured it. */
data class LlmCall(
    val promptTokens: Int,
    val prefillTokS: Double,
    val decodeTokens: Int,
    val decodeTokS: Double,
    val wallMs: Long,
)

/** The speed of the latest call, for "reading 1,240 tokens/s · writing 14 tokens/s" while a session is processed. */
data class LiveSpeed(val prefillTokS: Double, val decodeTokS: Double, val promptTokens: Int, val atMs: Long)

/** What finished sessions took, per minute of interview: the basis for time left on sessions that have not started. */
data class PerMinute(val sessions: Int, val processingMs: Double, val promptTokens: Double, val decodeTokens: Double)

/**
 * How fast this phone processes sessions, measured and kept so a status can show evidence instead of guesses: every model call's tokens
 * and speeds, and for every finished session how many tokens and how much time each minute of interview took. Numbers only, never text,
 * names or ids (sessions are only kept in memory while they run), so it lives in plain preferences next to the other settings.
 */
object PerfStats {
    /** Sessions measured before a time left is promised for one that has not started. */
    const val MIN_SESSIONS = 2

    private const val PREFS = "perf"
    private var prefs: SharedPreferences? = null

    private val _live = MutableStateFlow<LiveSpeed?>(null)
    val live: StateFlow<LiveSpeed?> = _live.asStateFlow()

    /** Bumped whenever something was measured, so screens re-read the averages. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Per running session (in memory only): tokens and time so far. */
    private class Running(var promptTokens: Long = 0, var decodeTokens: Long = 0, var wallMs: Long = 0, var calls: Int = 0)
    private val running = HashMap<String, Running>()
    private var current: String? = null
    private var currentKind: String = "other"

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // --- recording (the job runner and the model) ---

    @Synchronized
    fun jobStarted(sessionId: String, kind: String) {
        current = sessionId
        currentKind = kind
    }

    @Synchronized
    fun jobEnded(sessionId: String, wallMs: Long) {
        running.getOrPut(sessionId) { Running() }.wallMs += wallMs
        if (current == sessionId) current = null
    }

    /** A model call finished, for the job that is running (its kind: extract, commit). */
    @Synchronized
    fun call(model: String, c: LlmCall) {
        val p = prefs ?: return
        val k = "$model|$currentKind"
        p.edit()
            .putLong("$k|calls", p.getLong("$k|calls", 0) + 1)
            .putLong("$k|prompt", p.getLong("$k|prompt", 0) + c.promptTokens)
            .putLong("$k|decode", p.getLong("$k|decode", 0) + c.decodeTokens)
            .putLong("$k|wall", p.getLong("$k|wall", 0) + c.wallMs)
            .apply()
        current?.let { s -> running.getOrPut(s) { Running() }.apply { promptTokens += c.promptTokens; decodeTokens += c.decodeTokens; calls++ } }
        _live.value = LiveSpeed(c.prefillTokS, c.decodeTokS, c.promptTokens, System.currentTimeMillis())
        _version.value++
    }

    /** All of a session's jobs are done: what its [audioMs] of interview cost, per minute, joins the history. */
    @Synchronized
    fun sessionDone(sessionId: String, model: String, audioMs: Long) {
        val r = running.remove(sessionId) ?: return
        val p = prefs ?: return
        if (audioMs < 30_000 || r.wallMs <= 0) return // too short to say anything per minute
        val k = "session|$model"
        p.edit()
            .putLong("$k|count", p.getLong("$k|count", 0) + 1)
            .putLong("$k|audio", p.getLong("$k|audio", 0) + audioMs)
            .putLong("$k|wall", p.getLong("$k|wall", 0) + r.wallMs)
            .putLong("$k|prompt", p.getLong("$k|prompt", 0) + r.promptTokens)
            .putLong("$k|decode", p.getLong("$k|decode", 0) + r.decodeTokens)
            .apply()
        _version.value++
    }

    // --- reading (the screens) ---

    /** Average wall time of one call of [kind] on [model], and its prompt tokens; null before the first. */
    fun perCall(model: String, kind: String): Pair<Long, Long>? {
        val p = prefs ?: return null
        val k = "$model|$kind"
        val n = p.getLong("$k|calls", 0)
        if (n <= 0) return null
        return p.getLong("$k|wall", 0) / n to p.getLong("$k|prompt", 0) / n
    }

    /** What one minute of interview took on [model], once [MIN_SESSIONS] sessions were measured. */
    fun perMinute(model: String): PerMinute? {
        val p = prefs ?: return null
        val k = "session|$model"
        val n = p.getLong("$k|count", 0).toInt()
        val minutes = p.getLong("$k|audio", 0) / 60_000.0
        if (n < MIN_SESSIONS || minutes <= 0) return null
        return PerMinute(n, p.getLong("$k|wall", 0) / minutes, p.getLong("$k|prompt", 0) / minutes, p.getLong("$k|decode", 0) / minutes)
    }

    /** Sessions measured so far on [model]. */
    fun sessionsMeasured(model: String): Int = prefs?.getLong("session|$model|count", 0)?.toInt() ?: 0
}
