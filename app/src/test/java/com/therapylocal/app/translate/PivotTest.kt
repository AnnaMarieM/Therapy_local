package com.therapylocal.app.translate

import com.therapylocal.app.config.ProcessingLanguage
import com.therapylocal.app.facts.PromptLanguage
import com.therapylocal.app.facts.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PivotTest {
    private val modes = ProcessingLanguage.entries
    private val langs = listOf("en", "cs")

    @Test
    fun inputIsTranslatedOnlyInPivotModeForANonEnglishClient() {
        for (mode in modes) for (client in langs) {
            val expected = mode == ProcessingLanguage.English && client == "cs"
            assertEquals("$mode $client", expected, Pivot.translatesInput(mode, client))
        }
        assertTrue(Pivot.translatesInput(ProcessingLanguage.English, "CS"))
        assertFalse(Pivot.translatesInput(ProcessingLanguage.English, "EN"))
    }

    @Test
    fun pivotModeAlwaysUsesTheEnglishPrompts() {
        for (app in langs) for (client in langs) assertEquals("$app $client", PromptLanguage.EN, Pivot.promptLanguage(ProcessingLanguage.English, app, client))
    }

    @Test
    fun nativeModeChoosesPromptsAsBeforeThePivot() {
        for (app in langs) for (client in langs) assertEquals("$app $client", PromptLanguage.choose(app, client), Pivot.promptLanguage(ProcessingLanguage.Native, app, client))
        assertEquals(PromptLanguage.CS, Pivot.promptLanguage(ProcessingLanguage.Native, "cs", "cs"))
    }

    @Test
    fun outputIsTranslatedOnlyInPivotModeForANonEnglishApp() {
        for (mode in modes) for (app in langs) {
            val expected = mode == ProcessingLanguage.English && app == "cs"
            assertEquals("$mode $app", expected, Pivot.translatesOutput(mode, app))
        }
    }

    @Test
    fun theTagsOfTheTwoModesDifferForANonEnglishClient() {
        val pivot = Pivot.tag(ProcessingLanguage.English, "cs")
        val native = Pivot.tag(ProcessingLanguage.Native, "cs")
        assertNotEquals(pivot, native)
        // So every version that carries the tag differs, and a mode switch marks windows, facts and summaries stale.
        assertNotEquals(Pivot.version(pivot, "system", "user"), Pivot.version(native, "system", "user"))
        assertNotEquals(Pivot.windowCutVersion(pivot), Pivot.windowCutVersion(native))
        assertEquals(Pivot.version(pivot, "a"), Pivot.version(pivot, "a"))
        assertNotEquals(Pivot.version(pivot, "a"), Pivot.version(pivot, "b"))
    }

    @Test
    fun anEnglishClientIsProcessedTheSameWayInBothModes() {
        assertEquals(Pivot.tag(ProcessingLanguage.English, "en"), Pivot.tag(ProcessingLanguage.Native, "en"))
    }

    @Test
    fun theVersionIsAPromptVersionWithTheTagAsOnePart() {
        assertEquals(Prompts.version("s", "u", "t"), Pivot.version("t", "s", "u"))
        assertEquals(12, Pivot.version("t", "s").length)
    }

    @Test
    fun languageNamesAreEnglishWordsForThePrompts() {
        assertEquals("Czech", Pivot.languageName("cs"))
        assertEquals("Czech", Pivot.languageName("cs-CZ"))
        assertEquals("English", Pivot.languageName("en"))
        assertEquals("de", Pivot.languageName("de"))
    }

    @Test
    fun theProcessingLanguageReadsFromItsKeyAndDefaultsToEnglish() {
        assertEquals(ProcessingLanguage.Native, ProcessingLanguage.of("native"))
        assertEquals(ProcessingLanguage.English, ProcessingLanguage.of("english"))
        assertEquals(ProcessingLanguage.DEFAULT, ProcessingLanguage.of(null))
        assertEquals(ProcessingLanguage.DEFAULT, ProcessingLanguage.of("Native"))
        assertEquals(ProcessingLanguage.English, ProcessingLanguage.DEFAULT)
    }
}

class TranslationPromptsTest {
    private val dir = java.io.File("../demo/prompts")

    @Test
    fun theTranslatePromptAsksForTheMarkedLinesAndTakesTheLanguage() {
        val p = java.io.File(dir, Prompts.TRANSLATE_SYSTEM).readText()
        assertTrue("{{LANGUAGE}}" in p)
        assertTrue("[12]" in p)
        assertTrue("English" in p)
        assertFalse("{{TEXT}}" in p)
    }

    @Test
    fun theLocalizePromptAsksForTheMarkedLinesAndTakesTheLanguage() {
        val p = java.io.File(dir, Prompts.LOCALIZE_SYSTEM).readText()
        assertTrue("{{LANGUAGE}}" in p)
        assertTrue("[3]" in p)
        assertEquals("both prompts fill to a text without placeholders", false, Prompts.fill(p, mapOf("LANGUAGE" to "Czech")).contains("{{"))
    }

    @Test
    fun theTranslationPromptsHaveNoCzechTwinBecauseTheyAreAlwaysTheEnglishFiles() {
        assertFalse(java.io.File(dir, "cs/${Prompts.TRANSLATE_SYSTEM}").exists())
        assertFalse(java.io.File(dir, "cs/${Prompts.LOCALIZE_SYSTEM}").exists())
    }
}
