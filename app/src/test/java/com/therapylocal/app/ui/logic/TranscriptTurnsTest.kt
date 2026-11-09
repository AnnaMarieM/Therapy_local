package com.therapylocal.app.ui.logic

import com.therapylocal.app.data.Role
import com.therapylocal.app.ui.models.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTurnsTest {
    private fun line(idx: Int, speaker: String?) = TranscriptLine(idx, "00:0$idx", "text $idx", speaker)
    private val map = mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT)

    @Test
    fun aTurnStartsWhereTheSpeakerChanges() {
        val rows = TranscriptTurns.rows(listOf(line(1, "S0"), line(2, "S1"), line(3, "S1"), line(4, "S0")), map)
        assertEquals(listOf(true, true, false, true), rows.map { it.header })
    }

    @Test
    fun rolesComeFromTheMapAndSwappingTheMapSwapsThem() {
        val lines = listOf(line(1, "S0"), line(2, "S1"))
        assertEquals(listOf(Role.THERAPIST, Role.PATIENT), TranscriptTurns.rows(lines, map).map { it.role })
        val swapped = mapOf("S0" to Role.PATIENT, "S1" to Role.THERAPIST)
        assertEquals(listOf(Role.PATIENT, Role.THERAPIST), TranscriptTurns.rows(lines, swapped).map { it.role })
    }

    @Test
    fun withoutAMapTheSpeakersKeepTheirNumbers() {
        val rows = TranscriptTurns.rows(listOf(line(1, "S0"), line(2, "S1")), emptyMap())
        assertEquals(listOf(null, null), rows.map { it.role })
        assertEquals(listOf(1, 2), rows.map { it.number })
    }

    @Test
    fun anExcerptNumbersSpeakersLikeTheWholeTranscript() {
        assertEquals(2, TranscriptTurns.rows(listOf(line(7, "S1")), emptyMap()).single().number)
    }

    @Test
    fun anIdThatIsNotSNIsNumberedByFirstAppearance() {
        assertEquals(listOf(1, 2, 1), TranscriptTurns.rows(listOf(line(1, "x"), line(2, "y"), line(3, "x")), emptyMap()).map { it.number })
    }

    @Test
    fun linesWithoutASpeakerNeverStartATurn() {
        val rows = TranscriptTurns.rows(listOf(line(1, null), line(2, null)), map)
        assertTrue(rows.none { it.header })
        assertNull(rows[0].number)
        assertFalse(rows[0].header)
    }

    @Test
    fun theFirstLineShownStartsATurn() {
        assertTrue(TranscriptTurns.rows(listOf(line(5, "S1"), line(6, "S1")), map).first().header)
    }
}
