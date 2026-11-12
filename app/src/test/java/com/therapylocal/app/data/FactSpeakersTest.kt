package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roles are resolved from the raw id and the current map every time, so a swap changes what the default read shows, with nothing stored again. */
class FactSpeakersTest {
    private data class F(val id: Int, val session: String, val speaker: String?)

    private val facts = listOf(F(1, "s1", "S1"), F(2, "s1", "S0"), F(3, "s1", null), F(4, "s2", "S1"), F(5, "s2", "S7"))
    private val map = mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT)

    private fun ids(maps: Map<String, Map<String, Role>>, include: Boolean) =
        FactSpeakers.filter(facts, include, { it.session }, { it.speaker }, maps).map { it.id }

    @Test
    fun roleIsLookedUpInTheMapAndIsNullWhenItCannotBeResolved() {
        assertEquals(Role.PATIENT, FactSpeakers.roleOf("S1", map))
        assertEquals(Role.THERAPIST, FactSpeakers.roleOf("S0", map))
        assertNull(FactSpeakers.roleOf(null, map))
        assertNull(FactSpeakers.roleOf("S1", null))
        assertNull(FactSpeakers.roleOf("S9", map))
    }

    @Test
    fun theDefaultShowsThePatientAndWhatCannotBeResolvedAndHidesTheTherapistAndOthers() {
        val maps = mapOf("s1" to map, "s2" to mapOf("S1" to Role.PATIENT, "S7" to Role.OTHER))
        assertEquals(listOf(1, 3, 4), ids(maps, include = false))
        assertEquals(listOf(1, 2, 3, 4, 5), ids(maps, include = true))
    }

    @Test
    fun aSessionWithoutAMapShowsEverything() {
        assertEquals(listOf(1, 2, 3, 4, 5), ids(emptyMap(), include = false))
    }

    @Test
    fun aRolesSwapChangesWhatTheDefaultShowsWithoutTouchingTheFacts() {
        val before = mapOf("s1" to map, "s2" to map)
        assertEquals(listOf(1, 3, 4, 5), ids(before, false))
        val after = before.mapValues { (_, m) -> SpeakerMaps.swap(m) }
        // Same stored facts, same raw ids: S0 is the patient now, S1 the therapist.
        assertEquals(listOf(2, 3, 5), ids(after, false)) // 5 has an id the map does not know: unresolved, shown
        assertEquals(listOf(1, 2, 3, 4, 5), ids(after, true))
    }

    @Test
    fun theSwapIsAnAppendedMapSoTheLatestOneDecides() {
        var appended: String? = null
        SpeakerMaps.appendSwap(SpeakerMapRow("s1", 1, SpeakerMaps.encode(map), "llm", 0.9, "[]", 0)) { json, _, _, _ -> appended = json; 2 }
        val now = SpeakerMaps.decode(appended!!)
        assertEquals(Role.THERAPIST, FactSpeakers.roleOf("S1", now))
        assertTrue(FactSpeakers.visible(FactSpeakers.roleOf("S0", now), false))
        assertTrue(!FactSpeakers.visible(FactSpeakers.roleOf("S1", now), false))
    }
}
