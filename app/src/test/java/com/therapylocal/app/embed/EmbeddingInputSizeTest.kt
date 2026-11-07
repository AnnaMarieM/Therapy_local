package com.therapylocal.app.embed

import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.LiveCueSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingInputSizeTest {
    @Test fun theDefaultTwentySecondLookbackNeeds516TokensSoTheSizeIs1024() {
        assertEquals(516, EmbeddingConfig.audioTokens(20))
        assertEquals(1024, EmbeddingConfig.liveInputTokens(LiveCueSettings.DEFAULT_LOOKBACK_S))
    }

    @Test fun aShortLookbackStillHoldsAVoiceQuery() {
        assertEquals(EmbeddingConfig.liveInputTokens(20), EmbeddingConfig.liveInputTokens(5))
    }

    @Test fun theSizeGrowsWithTheLookbackAndTopsOutAtTheFullContext() {
        assertEquals(2048, EmbeddingConfig.liveInputTokens(81))
        assertEquals(4096, EmbeddingConfig.liveInputTokens(82))
        assertEquals(EmbeddingConfig.MAX_TOKENS, EmbeddingConfig.liveInputTokens(LiveCueSettings.LOOKBACK_S.last))
    }

    @Test fun theChosenSizeAlwaysHoldsTheLookback() {
        for (s in LiveCueSettings.LOOKBACK_S) assertTrue("lookback $s", EmbeddingConfig.maxAudioSeconds(EmbeddingConfig.liveInputTokens(s)) >= s)
    }

    @Test fun sizesRoundUpToABuildSize() {
        assertEquals(128, EmbeddingConfig.inputSizeFor(1))
        assertEquals(256, EmbeddingConfig.inputSizeFor(129))
        assertEquals(8192, EmbeddingConfig.inputSizeFor(9000))
    }
}
