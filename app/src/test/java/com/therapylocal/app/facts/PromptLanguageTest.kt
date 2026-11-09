package com.therapylocal.app.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptLanguageTest {
    @Test
    fun czechAppAndCzechClientUseCzechPrompts() {
        assertEquals(PromptLanguage.CS, PromptLanguage.choose("cs", "cs"))
    }

    @Test
    fun anEnglishAppKeepsEnglishPromptsEvenForACzechClient() {
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("en", "cs"))
    }

    @Test
    fun aCzechAppWithAnEnglishClientKeepsEnglishPrompts() {
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("cs", "en"))
    }

    @Test
    fun theLanguageTagsAreCaseInsensitive() {
        assertEquals(PromptLanguage.CS, PromptLanguage.choose("CS", "CS"))
    }

    @Test
    fun czechPromptFilesAreNamedInTheCsFolder() {
        assertEquals("cs/extract.system.txt", Prompts.named(Prompts.EXTRACT_SYSTEM, PromptLanguage.CS))
        assertEquals("extract.system.txt", Prompts.named(Prompts.EXTRACT_SYSTEM, PromptLanguage.EN))
    }

    @Test
    fun theCzechPromptsKeepThePlaceholdersAndOutputFormatOfTheEnglishOnes() {
        val dir = File("../demo/prompts")
        val placeholder = Regex("\\{\\{([A-Z]+)}}")
        for (name in listOf(Prompts.EXTRACT_SYSTEM, Prompts.EXTRACT_USER, Prompts.REVIEW_SYSTEM, Prompts.REVIEW_USER)) {
            val en = File(dir, name).readText()
            val cs = File(dir, "cs/$name").readText()
            assertEquals(name, placeholder.findAll(en).map { it.groupValues[1] }.toSet(), placeholder.findAll(cs).map { it.groupValues[1] }.toSet())
        }
        // The parsers rely on these keys, so the Czech prompts must ask for exactly them.
        val extract = File(dir, "cs/${Prompts.EXTRACT_SYSTEM}").readText()
        for (key in listOf("\"heading\"", "\"content\"", "\"evidence\"")) assertTrue(key, key in extract)
        val review = File(dir, "cs/${Prompts.REVIEW_SYSTEM}").readText()
        for (key in listOf("\"duplicate\"", "\"of\"")) assertTrue(key, key in review)
    }

    @Test
    fun theRolePromptsAskForTheSameJsonKeysAndTheIdsAsGiven() {
        val dir = File("../demo/prompts")
        val en = File(dir, Prompts.ROLES_SYSTEM).readText()
        val cs = File(dir, "cs/${Prompts.ROLES_SYSTEM}").readText()
        // RoleGuess.parse reads exactly these keys.
        for (key in listOf("\"therapist\"", "\"patient\"", "\"confidence\"", "\"evidence\"")) {
            assertTrue(key, key in en)
            assertTrue(key, key in cs)
        }
        // The ids are checked against the real ones, so both prompts must say to copy them as written.
        assertTrue("exactly" in en && "S0" in en)
        assertTrue("přesně" in cs && "S0" in cs)
    }
}
