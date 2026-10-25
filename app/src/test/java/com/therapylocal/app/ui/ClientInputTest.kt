package com.therapylocal.app.ui

import com.therapylocal.app.ui.data.ClientInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClientInputTest {
    @Test
    fun slugLowercasesAndStripsAccents() {
        assertEquals("alexexample", ClientInput.slug("Alex Example"))
        assertEquals("jorgmuller", ClientInput.slug("  Jörg   Müller "))
        assertEquals("", ClientInput.slug("...!"))
    }

    @Test
    fun slugCaseAndPunctuationRules() {
        assertEquals("sarahchen", ClientInput.slug("SARAH chen"))
        assertEquals("marcusvancephd", ClientInput.slug("Marcus Vance, PhD"))
        assertEquals("abc123", ClientInput.slug("a-b_c 1.2/3"))
        assertEquals("o", ClientInput.slug("O'"))
        assertEquals("ab", ClientInput.slug("a\tb\n"))
    }

    @Test
    fun slugDecomposesCommonAccents() {
        assertEquals("rene", ClientInput.slug("René"))
        assertEquals("francoise", ClientInput.slug("Françoise"))
        assertEquals("nunez", ClientInput.slug("Núñez"))
        // Pre-composed and decomposed input give the same slug.
        assertEquals(ClientInput.slug("é"), ClientInput.slug("é"))
    }

    @Test
    fun slugDropsCharactersWithoutAsciiDecomposition() {
        // No transliteration: sharp s, o-slash and CJK vanish rather than being mapped.
        assertEquals("strae", ClientInput.slug("Straße"))
        assertEquals("sren", ClientInput.slug("Søren"))
        assertEquals("", ClientInput.slug("山田"))
    }

    @Test
    fun slugOfEmptyWhitespaceAndEmojiIsEmpty() {
        assertEquals("", ClientInput.slug(""))
        assertEquals("", ClientInput.slug("   \t\n"))
        assertEquals("", ClientInput.slug("😀👍"))
        assertEquals("ab", ClientInput.slug("a😀b"))
    }

    @Test
    fun slugOfVeryLongNameKeepsStableLowercasePrefix() {
        // Deliberately no length assertion: whether ids are capped is a product decision.
        val slug = ClientInput.slug("Ab ".repeat(5000))
        assertTrue(slug.all { it in 'a'..'z' })
        assertTrue(slug.startsWith("ababab"))
    }

    @Test
    fun slugIsIdempotentAndAlwaysAsciiAlnum() {
        val rnd = Random(1234)
        val alphabet = "abcXYZ019 ,.-_'éüßñ中😀\t́"
        repeat(500) {
            val name = String(CharArray(rnd.nextInt(0, 30)) { alphabet[rnd.nextInt(alphabet.length)] })
            val s = ClientInput.slug(name)
            assertTrue("'$s' from '$name'", s.matches(Regex("[a-z0-9]*")))
            assertEquals(s, ClientInput.slug(s))
        }
    }

    @Test
    fun uniqueIdAddsSuffixOnCollision() {
        assertEquals("marta", ClientInput.uniqueId("marta", setOf("george")))
        assertEquals("marta3", ClientInput.uniqueId("marta", setOf("marta", "marta2")))
    }

    @Test
    fun uniqueIdFirstSuffixIsTwo() {
        assertEquals("marta2", ClientInput.uniqueId("marta", setOf("marta")))
        assertEquals("marta", ClientInput.uniqueId("marta", emptySet()))
    }

    @Test
    fun uniqueIdFillsFirstGapNotLast() {
        assertEquals("marta2", ClientInput.uniqueId("marta", setOf("marta", "marta3")))
        assertEquals("marta4", ClientInput.uniqueId("marta", setOf("marta", "marta2", "marta3")))
    }

    @Test
    fun uniqueIdWithManyCollisions() {
        val taken = (listOf("marta") + (2..1000).map { "marta$it" }).toSet()
        assertEquals("marta1001", ClientInput.uniqueId("marta", taken))
        assertEquals("marta1002", ClientInput.uniqueId("marta", taken + "marta1001"))
    }

    @Test
    fun uniqueIdSuffixedBaseCanCollideWithOtherClient() {
        val taken = setOf("marta2")
        val id = ClientInput.uniqueId("marta2", taken)
        assertEquals("marta22", id)
        assertFalse(id in taken)
    }

    @Test
    fun uniqueIdResultIsNeverTakenAndKeepsBasePrefix() {
        val rnd = Random(99)
        repeat(200) {
            val taken = (0 until rnd.nextInt(0, 40)).map { if (it == 0) "x" else "x${rnd.nextInt(2, 30)}" }.toSet()
            val id = ClientInput.uniqueId("x", taken)
            assertFalse(id in taken)
            assertTrue(id.startsWith("x"))
        }
    }

    @Test
    fun uniqueIdOfEmptyBase() {
        assertEquals("", ClientInput.uniqueId("", emptySet()))
        assertEquals("2", ClientInput.uniqueId("", setOf("")))
    }

    @Test
    fun validateChecksName() {
        assertNull(ClientInput.validate("Marta"))
        assertNotNull(ClientInput.validate("  "))
    }

    @Test
    fun validateWhitespaceEmojiAndPunctuationOnlyAreRejected() {
        assertNotNull(ClientInput.validate(""))
        assertNotNull(ClientInput.validate("\t \n"))
        assertNotNull(ClientInput.validate("..."))
        assertNotNull(ClientInput.validate("😀"))
        assertNotNull(ClientInput.validate("山田"))
        assertEquals("Enter a name", ClientInput.validate(""))
    }

    @Test
    fun validateAcceptsNamesWithAtLeastOneAsciiLetterOrDigit() {
        assertNull(ClientInput.validate("  a  "))
        assertNull(ClientInput.validate("7"))
        assertNull(ClientInput.validate("É"))          // decomposes to E
        assertNull(ClientInput.validate("😀 x"))
    }
}
