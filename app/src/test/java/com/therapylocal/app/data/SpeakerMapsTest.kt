package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerMapsTest {
    private val map = mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT, "S2" to Role.OTHER)

    @Test
    fun jsonRoundTrips() {
        assertEquals(map, SpeakerMaps.decode(SpeakerMaps.encode(map)))
        assertEquals("""{"S0":"therapist","S1":"patient","S2":"other"}""", SpeakerMaps.encode(map))
    }

    @Test
    fun emptyMapRoundTrips() = assertEquals(emptyMap<String, Role>(), SpeakerMaps.decode(SpeakerMaps.encode(emptyMap())))

    @Test
    fun malformedJsonDecodesToEmpty() {
        for (bad in listOf("", "not json", "[1,2]", "{\"S0\":", "null")) assertEquals(bad, emptyMap<String, Role>(), SpeakerMaps.decode(bad))
    }

    @Test
    fun unknownRoleNamesAreOtherAndCaseIsIgnored() =
        assertEquals(mapOf("S0" to Role.OTHER, "S1" to Role.THERAPIST), SpeakerMaps.decode("""{"S0":"doctor","S1":" Therapist "}"""))

    @Test
    fun swapTradesTherapistAndPatient() {
        assertEquals(mapOf("S0" to Role.PATIENT, "S1" to Role.THERAPIST, "S2" to Role.OTHER), SpeakerMaps.swap(map))
        assertEquals(map, SpeakerMaps.swap(SpeakerMaps.swap(map)))
    }

    @Test
    fun canSwapNeedsExactlyOneOfEach() {
        assertTrue(SpeakerMaps.canSwap(map))
        assertFalse(SpeakerMaps.canSwap(emptyMap()))
        assertFalse(SpeakerMaps.canSwap(mapOf("S0" to Role.THERAPIST)))
        assertFalse(SpeakerMaps.canSwap(mapOf("S0" to Role.THERAPIST, "S1" to Role.THERAPIST, "S2" to Role.PATIENT)))
        assertFalse(SpeakerMaps.canSwap(mapOf("S0" to Role.OTHER, "S1" to Role.OTHER)))
    }

    @Test
    fun swapLeavesAMapThatCannotBeSwappedAlone() {
        val m = mapOf("S0" to Role.THERAPIST, "S1" to Role.THERAPIST)
        assertEquals(m, SpeakerMaps.swap(m))
    }
}
