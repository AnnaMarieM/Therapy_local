package com.therapylocal.app.data

import com.therapylocal.app.ui.models.SpeakerRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Is this incorrect?" swap appends a version and keeps the history; the screen's read model of a map. */
class SpeakerSwapTest {
    private class Maps {
        val rows = ArrayList<SpeakerMapRow>()
        fun append(json: String, source: String, confidence: Double, evidence: String): Int {
            val v = rows.size + 1
            rows += SpeakerMapRow("s", v, json, source, confidence, evidence, 0)
            return v
        }
    }

    private val llm = SpeakerMapRow("s", 1, SpeakerMaps.encode(mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT)), "llm", 0.8, """["How are you?"]""", 0)

    @Test
    fun aSwapAppendsTheNextVersionWithTheRolesExchanged() {
        val maps = Maps().also { it.rows += llm }
        val v = SpeakerMaps.appendSwap(maps.rows.last(), maps::append)
        assertEquals(2, v)
        val now = maps.rows.last()
        assertEquals(mapOf("S0" to Role.PATIENT, "S1" to Role.THERAPIST), SpeakerMaps.decode(now.mapJson))
        assertEquals("user", now.source)
        assertEquals(1.0, now.confidence, 0.0)
        // History is kept: version 1 is untouched.
        assertEquals(llm, maps.rows.first())
        // Swapping again goes back, as a third version.
        assertEquals(3, SpeakerMaps.appendSwap(now, maps::append))
        assertEquals(SpeakerMaps.decode(llm.mapJson), SpeakerMaps.decode(maps.rows.last().mapJson))
    }

    @Test
    fun noMapOrANonSwappableMapAppendsNothing() {
        val maps = Maps()
        assertNull(SpeakerMaps.appendSwap(null, maps::append))
        val twoTherapists = llm.copy(mapJson = SpeakerMaps.encode(mapOf("S0" to Role.THERAPIST, "S1" to Role.THERAPIST)))
        assertNull(SpeakerMaps.appendSwap(twoTherapists, maps::append))
        assertTrue(maps.rows.isEmpty())
    }

    @Test
    fun theReadModelCarriesTheMapSourceConfidenceEvidenceAndVersion() {
        val r = SpeakerRoles.of(llm)
        assertEquals(mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT), r.map)
        assertEquals("llm", r.source)
        assertEquals(0.8, r.confidence, 0.0)
        assertEquals(listOf("How are you?"), r.evidence)
        assertTrue(r.canSwap)
        assertEquals(1, r.version)
    }

    @Test
    fun aMapWithOnlyOtherSpeakersCannotBeSwapped() {
        val r = SpeakerRoles.of(llm.copy(mapJson = SpeakerMaps.encode(mapOf("S0" to Role.OTHER, "S1" to Role.OTHER)), evidenceJson = "not json"))
        assertFalse(r.canSwap)
        assertEquals(emptyList<String>(), r.evidence)
    }
}
