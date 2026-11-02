package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguageTest {
    @Test
    fun englishUsesTheEnglishOnlyBuildAndCzechTheMultilingualOne() {
        assertEquals("whisper-base.en", ModelRegistry.whisperFor("base", Language.English).id)
        assertEquals("whisper-base", ModelRegistry.whisperFor("base", Language.Czech).id)
        assertEquals("whisper-small.en", ModelRegistry.whisperFor("small", Language.English).id)
        assertEquals("whisper-tiny", ModelRegistry.whisperFor("tiny", Language.Czech).id)
    }

    @Test
    fun theBigBuildsServeEveryLanguage() {
        for (size in listOf("medium", "turbo", "large-v3")) {
            assertEquals("whisper-$size", ModelRegistry.whisperFor(size, Language.Czech).id)
            assertEquals("whisper-$size", ModelRegistry.whisperFor(size, Language.English).id)
        }
    }

    @Test
    fun anUnknownSizeFallsBackToBase() {
        assertEquals("whisper-base", ModelRegistry.whisperFor("huge", Language.Czech).id)
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
        assertEquals(Language.Czech, Language.of("cs"))
        assertEquals(Language.English, Language.of("sk"))
        assertEquals(Language.English, Language.of(null))
        assertNotEquals(Language.of("cs"), Language.of("en"))
    }
}
