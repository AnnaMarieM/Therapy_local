package com.therapylocal.app.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptLanguageTest {
    private val czech = "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené. " +
        "Usínám až po půlnoci a v pět ráno se budím. Pila jsem proto víc kávy, tři až čtyři hrnky denně. V pátek mi šéf řekl, že dostanu výpověď. " +
        "Poslední den budu mít třicátého září. Bojím se, jak zaplatím nájem, platím osm tisíc měsíčně. Chodím na dlouhé procházky podél řeky."
    private val slovak = "Moja sestra Lucia sa presťahovala do Bratislavy a mám pocit, že sme si teraz veľmi vzdialené. " +
        "Zaspávam až po polnoci a o piatej ráno sa budím. Pila som preto viac kávy, tri až štyri hrnčeky denne. V piatok mi šéf povedal, že dostanem výpoveď. " +
        "Posledný deň budem mať tridsiateho septembra. Bojím sa, ako zaplatím nájom, platím osem tisíc mesačne. Chodím na dlhé prechádzky popri rieke."
    private val english = "My sister Lucie moved to Brno and I feel that we are now terribly distant from each other. " +
        "I fall asleep only after midnight and I wake up at five in the morning. I have been drinking more coffee, three or four cups a day. " +
        "On Friday my boss told me that I would be fired. My last day will be the thirtieth of September. I am afraid of how I will pay the rent."

    @Test
    fun czechAppAndCzechClientUseCzechPrompts() {
        assertEquals(PromptLanguage.CS, PromptLanguage.choose("cs", "cs", ""))
    }

    @Test
    fun anEnglishAppKeepsEnglishPromptsEvenForACzechClient() {
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("en", "cs", czech))
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("en", "auto", czech))
    }

    @Test
    fun aCzechAppWithAnotherLanguageClientKeepsEnglishPrompts() {
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("cs", "en", english))
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("cs", "sk", slovak))
    }

    @Test
    fun withAutoTheTranscriptDecides() {
        assertEquals(PromptLanguage.CS, PromptLanguage.choose("cs", "auto", czech))
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("cs", "auto", english))
        assertEquals(PromptLanguage.EN, PromptLanguage.choose("cs", "auto", slovak))
    }

    @Test
    fun aTextTooShortToJudgeIsNotCzech() {
        assertFalse(PromptLanguage.looksCzech("Přestěhovala se do Brna."))
        assertFalse(PromptLanguage.looksCzech(""))
    }

    @Test
    fun theAppLanguageTagIsCaseInsensitive() {
        assertEquals(PromptLanguage.CS, PromptLanguage.choose("CS", "CS", ""))
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
}
