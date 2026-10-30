package com.therapylocal.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCueSettingsTest {
    @Test
    fun defaultsMatchThePlan() {
        val s = LiveCueSettings.DEFAULT
        assertEquals(2_000L, s.intervalMs)
        assertEquals(20, s.lookbackS)
        assertEquals(0.5f, s.smoothing, 0f)
        assertEquals(0f, s.scoreFloor, 0f)
        assertEquals(20 * ChunkConfig.SAMPLE_RATE, s.lookbackSamples)
    }

    @Test
    fun valuesBelowTheRangeAreRaisedToTheMinimum() {
        val s = LiveCueSettings.of(intervalMs = 0, lookbackS = -3, smoothing = 0f, scoreFloor = -1f)
        assertEquals(1_000L, s.intervalMs)
        assertEquals(5, s.lookbackS)
        assertEquals(0.05f, s.smoothing, 0f)
        assertEquals(0f, s.scoreFloor, 0f)
    }

    @Test
    fun valuesAboveTheRangeAreLoweredToTheMaximum() {
        val s = LiveCueSettings.of(intervalMs = 60_000, lookbackS = 999, smoothing = 2f, scoreFloor = 1.5f)
        assertEquals(10_000L, s.intervalMs)
        assertEquals(LiveCueConfig.MAX_LOOKBACK_S, s.lookbackS)
        assertEquals(1f, s.smoothing, 0f)
        assertEquals(1f, s.scoreFloor, 0f)
    }

    @Test
    fun valuesInsideTheRangeAreKept() {
        val s = LiveCueSettings.of(intervalMs = 3_500, lookbackS = 45, smoothing = 0.2f, scoreFloor = 0.6f)
        assertEquals(3_500L, s.intervalMs)
        assertEquals(45, s.lookbackS)
        assertEquals(0.2f, s.smoothing, 0f)
        assertEquals(0.6f, s.scoreFloor, 0f)
    }

    @Test
    fun notANumberFallsBackToTheDefault() {
        val s = LiveCueSettings.of(smoothing = Float.NaN, scoreFloor = Float.NaN)
        assertEquals(LiveCueSettings.DEFAULT_SMOOTHING, s.smoothing, 0f)
        assertEquals(LiveCueSettings.DEFAULT_SCORE_FLOOR, s.scoreFloor, 0f)
    }

    @Test
    fun theFullContextHoldsAboutFiveAndAHalfMinutesOfAudio() {
        // The model card gives ~327 s for 8,192 tokens at 25 tokens per second.
        assertEquals(327, EmbeddingConfig.maxAudioSeconds(8192))
        assertEquals(81, EmbeddingConfig.maxAudioSeconds(2048))
        assertEquals(0, EmbeddingConfig.maxAudioSeconds(8))
    }

    @Test
    fun theLookbackCanReachTheEmbeddersLimitButNotBeyond() {
        assertEquals(EmbeddingConfig.maxAudioSeconds(EmbeddingConfig.MAX_TOKENS), LiveCueSettings.LOOKBACK_S.last)
        assertEquals(327, LiveCueSettings.of(lookbackS = 10_000).lookbackS)
    }
}
