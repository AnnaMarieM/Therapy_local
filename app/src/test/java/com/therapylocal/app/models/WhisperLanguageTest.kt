package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguageTest {
    @Test
    fun englishUsesTheEnglishOnlyBuildAndCzechTheMultilingualOne() {
        assertEquals("whisper-base.en", ModelRegistry.whisperFor("base", SpeechLanguage.English).id)
        assertEquals("whisper-base", ModelRegistry.whisperFor("base", SpeechLanguage.Czech).id)
        assertEquals("whisper-small.en", ModelRegistry.whisperFor("small", SpeechLanguage.English).id)
        assertEquals("whisper-tiny", ModelRegistry.whisperFor("tiny", SpeechLanguage.Czech).id)
    }

    @Test
    fun theBigBuildsServeEveryLanguage() {
        for (size in listOf("medium", "turbo", "large-v3")) {
            assertEquals("whisper-$size", ModelRegistry.whisperFor(size, SpeechLanguage.Czech).id)
            assertEquals("whisper-$size", ModelRegistry.whisperFor(size, SpeechLanguage.English).id)
        }
    }

    @Test
    fun anUnknownSizeFallsBackToBase() {
        assertEquals("whisper-base", ModelRegistry.whisperFor("huge", SpeechLanguage.Czech).id)
    }

    @Test
    fun everyWhisperBuildHasItsOwnFolderAndNamedFiles() {
        val specs = ModelRegistry.ALL.filter { it.id.startsWith("whisper-") }
        assertEquals(9, specs.size)
        assertEquals(specs.size, specs.map { it.subdir }.toSet().size)
        specs.forEach { s ->
            val prefix = s.id.removePrefix("whisper-")
            assertTrue(s.files.all { it.name.startsWith("$prefix-") && it.sha256.length == 64 })
            assertEquals(3, s.files.size)
        }
    }

    @Test
    fun aCodeThatIsNotSupportedIsEnglish() {
        assertEquals(SpeechLanguage.Czech, SpeechLanguage.of("cs"))
        assertEquals(SpeechLanguage.English, SpeechLanguage.of("sk"))
        assertEquals(SpeechLanguage.English, SpeechLanguage.of(null))
        assertNotEquals(SpeechLanguage.of("cs"), SpeechLanguage.of("en"))
    }

    @Test
    fun canaryIsTheDefaultEngineAndHasThreeCheckedFiles() {
        assertEquals("canary", ModelRegistry.DEFAULT_ENGINE)
        val c = ModelRegistry.CANARY_1B_V2
        assertEquals("canary-1b-v2", c.id)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"), c.files.map { it.name })
        assertTrue(c.files.all { it.sha256.length == 64 && it.url.contains("/resolve/main/${it.name}") })
        assertEquals(c, ModelRegistry.byId("canary-1b-v2"))
    }
}
