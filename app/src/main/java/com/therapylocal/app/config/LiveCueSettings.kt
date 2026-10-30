package com.therapylocal.app.config

import android.content.SharedPreferences

/** The adjustable live-cue knobs. Always built through [of], so every value is inside its range. */
class LiveCueSettings private constructor(
    /** Pause between ticks. */
    val intervalMs: Long,
    /** Audio embedded per tick: the last this many seconds. */
    val lookbackS: Int,
    /** Weight of the newest tick in the per-fact score average. 1.0 = no smoothing. */
    val smoothing: Float,
    /** Smoothed cosine a fact needs to be shown. */
    val scoreFloor: Float,
) {
    val lookbackSamples get() = lookbackS * ChunkConfig.SAMPLE_RATE

    companion object {
        const val DEFAULT_INTERVAL_MS = 2_000L
        const val DEFAULT_LOOKBACK_S = 20
        const val DEFAULT_SMOOTHING = 0.5f
        const val DEFAULT_SCORE_FLOOR = 0f

        val INTERVAL_MS = 1_000L..10_000L
        val LOOKBACK_S = 5..LiveCueConfig.MAX_LOOKBACK_S
        val SMOOTHING = 0.05f..1f
        val SCORE_FLOOR = 0f..1f

        const val KEY_INTERVAL_MS = "live_cue_interval_ms"
        const val KEY_LOOKBACK_S = "live_cue_lookback_s"
        const val KEY_SMOOTHING = "live_cue_smoothing"
        const val KEY_SCORE_FLOOR = "live_cue_score_floor"

        val DEFAULT = of()

        fun of(
            intervalMs: Long = DEFAULT_INTERVAL_MS,
            lookbackS: Int = DEFAULT_LOOKBACK_S,
            smoothing: Float = DEFAULT_SMOOTHING,
            scoreFloor: Float = DEFAULT_SCORE_FLOOR,
        ) = LiveCueSettings(
            intervalMs.coerceIn(INTERVAL_MS),
            lookbackS.coerceIn(LOOKBACK_S),
            smoothing.orDefault(DEFAULT_SMOOTHING).coerceIn(SMOOTHING),
            scoreFloor.orDefault(DEFAULT_SCORE_FLOOR).coerceIn(SCORE_FLOOR),
        )

        private fun Float.orDefault(d: Float) = if (isNaN()) d else this
    }
}

fun SharedPreferences.liveCueSettings() = LiveCueSettings.of(
    getLong(LiveCueSettings.KEY_INTERVAL_MS, LiveCueSettings.DEFAULT_INTERVAL_MS),
    getInt(LiveCueSettings.KEY_LOOKBACK_S, LiveCueSettings.DEFAULT_LOOKBACK_S),
    getFloat(LiveCueSettings.KEY_SMOOTHING, LiveCueSettings.DEFAULT_SMOOTHING),
    getFloat(LiveCueSettings.KEY_SCORE_FLOOR, LiveCueSettings.DEFAULT_SCORE_FLOOR),
)

fun SharedPreferences.saveLiveCueSettings(s: LiveCueSettings) = edit()
    .putLong(LiveCueSettings.KEY_INTERVAL_MS, s.intervalMs)
    .putInt(LiveCueSettings.KEY_LOOKBACK_S, s.lookbackS)
    .putFloat(LiveCueSettings.KEY_SMOOTHING, s.smoothing)
    .putFloat(LiveCueSettings.KEY_SCORE_FLOOR, s.scoreFloor)
    .apply()
