package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canary is the only speech recogniser (docs/AD/AD-02.md): one download serves every language. */
class SpeechSetupTest {
    @Test
    fun aCodeThatIsNotSupportedIsEnglish() {
        assertEquals(SpeechLanguage.Czech, SpeechLanguage.of("cs"))
        assertEquals(SpeechLanguage.English, SpeechLanguage.of("sk"))
        assertEquals(SpeechLanguage.English, SpeechLanguage.of(null))
        assertNotEquals(SpeechLanguage.of("cs"), SpeechLanguage.of("en"))
    }

    @Test
    fun canaryHasThreeCheckedFiles() {
        val c = ModelRegistry.CANARY_1B_V2
        assertEquals("canary-1b-v2", c.id)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"), c.files.map { it.name })
        assertTrue(c.files.all { it.sha256.length == 64 && it.url.contains("/resolve/main/${it.name}") })
        assertEquals(c, ModelRegistry.byId("canary-1b-v2"))
    }

    @Test
    fun theOnlySpeechModelsAreCanaryAndWhisperLargeV3ForTheQualityWorkflow() {
        // AD-02 allowed Canary only; AD-22 adds Whisper large-v3 q8_0 for the Quality workflow. No other recogniser is registered.
        val speech = ModelRegistry.ALL.filter { it.id.startsWith("canary") || it.id.contains("whisper") || it.id.contains("parakeet") }
        assertEquals(listOf("canary-1b-v2", "whisper-large-v3-q8_0"), speech.map { it.id })
    }
}
