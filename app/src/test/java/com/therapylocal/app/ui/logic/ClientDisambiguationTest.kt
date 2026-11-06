package com.therapylocal.app.ui.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientDisambiguationTest {

    @Test
    fun singleClientWithSurnameKeepsOnlyFirstInitial() {
        val targets = listOf(
            DisambiguationTarget("1", "George", "Miller", 100L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("George M.", result["1"])
    }

    @Test
    fun clientsWithDifferentFirstInitialsKeepSingleLetter() {
        val targets = listOf(
            DisambiguationTarget("1", "John", "Doe", 100L),
            DisambiguationTarget("2", "John", "Smith", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("John D.", result["1"])
        assertEquals("John S.", result["2"])
    }

    @Test
    fun johnDoeAndJohnDillExpandToFirstDifferingLetter() {
        val targets = listOf(
            DisambiguationTarget("1", "John", "Doe", 100L),
            DisambiguationTarget("2", "John", "Dill", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("John Do.", result["1"])
        assertEquals("John Di.", result["2"])
    }

    @Test
    fun karelNovotnyAndKarelNovakExpandToFourLetters() {
        val targets = listOf(
            DisambiguationTarget("1", "Karel", "Novotny", 100L),
            DisambiguationTarget("2", "Karel", "Novak", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("Karel Novo.", result["1"])
        assertEquals("Karel Nova.", result["2"])
    }

    @Test
    fun janNinaAndTwoJanNovakFullDuplicate() {
        val targets = listOf(
            DisambiguationTarget("1", "Jan", "Nina", 100L),
            DisambiguationTarget("2", "Jan", "Novak", 200L),
            DisambiguationTarget("3", "Jan", "Novak", 300L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("Jan Ni.", result["1"])
        assertEquals("Jan No. I.", result["2"])
        assertEquals("Jan No. II.", result["3"])
    }

    @Test
    fun identicalFullNamesGetRomanNumeralsChronologically() {
        val targets = listOf(
            DisambiguationTarget("1", "John", "Doe", 100L),
            DisambiguationTarget("2", "John", "Doe", 200L),
            DisambiguationTarget("3", "John", "Doe", 300L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("John D. I.", result["1"])
        assertEquals("John D. II.", result["2"])
        assertEquals("John D. III.", result["3"])
    }

    @Test
    fun previewNewClientAgainstExisting() {
        val existing = listOf(
            DisambiguationTarget("1", "John", "Doe", 100L)
        )
        val previewNew = ClientDisambiguation.preview("John", "Dill", existing)
        assertEquals("John Di.", previewNew)

        val previewDuplicate = ClientDisambiguation.preview("John", "Doe", existing)
        assertEquals("John D. II.", previewDuplicate)
    }

    @Test
    fun clientWithoutSurname() {
        val targets = listOf(
            DisambiguationTarget("1", "Cher", "", 100L),
            DisambiguationTarget("2", "Madonna", "", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("Cher", result["1"])
        assertEquals("Madonna", result["2"])
    }

    @Test
    fun twoClientsWithSameFirstNameAndNoSurname() {
        val targets = listOf(
            DisambiguationTarget("1", "Ana", "", 100L),
            DisambiguationTarget("2", "Ana", "", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("Ana I.", result["1"])
        assertEquals("Ana II.", result["2"])
    }

    @Test
    fun diacriticsHandling() {
        val targets = listOf(
            DisambiguationTarget("1", "Jan", "Černý", 100L),
            DisambiguationTarget("2", "Jan", "Čermák", 200L)
        )
        val result = ClientDisambiguation.disambiguate(targets)
        assertEquals("Jan Čern.", result["1"])
        assertEquals("Jan Čerm.", result["2"])
    }
}

