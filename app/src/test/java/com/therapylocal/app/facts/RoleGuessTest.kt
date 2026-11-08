package com.therapylocal.app.facts

import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleGuessTest {
    private val segs = listOf(
        SegmentRow(1, 0, 4000, "Hello, how have you been this week?", "S0"),
        SegmentRow(2, 4000, 9000, "Not great. I could not sleep at all.", "S1"),
        SegmentRow(3, 9000, 12_000, "Tell me more about that.", "S0"),
    )
    private val input = RoleGuess.input(segs)
    private val real = listOf("S0", "S1")

    private fun parse(reply: String, speakers: List<String> = real) = RoleGuess.parse(reply, speakers, input)

    @Test
    fun inputUsesRawIdsAndTheTranscriptFormat() {
        assertEquals("[00:00:00]\nS0: Hello, how have you been this week?\n\n[00:00:04]\nS1: Not great. I could not sleep at all.\n\n[00:00:09]\nS0: Tell me more about that.", input)
    }

    @Test
    fun inputOfNothingIsEmpty() = assertEquals("", RoleGuess.input(emptyList()))

    @Test
    fun inputShowsAnUnknownSpeakerAsQuestionMark() = assertEquals("[00:00:00]\n?: hi", RoleGuess.input(listOf(SegmentRow(1, 0, 1, "hi"))))

    @Test
    fun inputIsCutAtAnUtteranceBoundary() {
        val long = (1..50).map { SegmentRow(it, it * 1000L, it * 1000L + 900, "w1 w2 w3 w4 w5 w6 w7 w8 w9 w10", "S${it % 2}") }
        val cut = RoleGuess.input(long, maxTokens = 140) // 100 words = 10 utterances
        assertEquals(10, cut.split("\n\n").size)
        assertTrue(cut.split("\n\n").all { it.endsWith("w10") })
    }

    @Test
    fun inputKeepsTheFirstUtteranceEvenWhenItIsOverBudget() {
        val one = listOf(SegmentRow(1, 0, 1, (1..500).joinToString(" ") { "w$it" }, "S0"), SegmentRow(2, 1, 2, "x", "S1"))
        assertEquals(1, RoleGuess.input(one, maxTokens = 10).split("\n\n").size)
    }

    @Test
    fun schemaIsValidJsonWithTheFourFields() {
        val o = JSONObject(RoleGuess.SCHEMA)
        assertEquals(setOf("therapist", "patient", "confidence", "evidence"), o.getJSONObject("properties").keys().asSequence().toSet())
        assertEquals(4, o.getJSONArray("required").length())
    }

    @Test
    fun aGoodReplyIsAccepted() {
        val g = parse("""{"therapist":"S0","patient":"S1","confidence":0.8,"evidence":["how have you been this week"]}""")!!
        assertEquals(mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT), g.map)
        assertEquals(0.8, g.confidence, 0.0)
        assertEquals(listOf("how have you been this week"), g.evidence)
    }

    @Test
    fun codeFencesAndSurroundingTextAreTolerated() {
        val json = """{"therapist":"S1","patient":"S0","confidence":0.5,"evidence":[]}"""
        assertEquals(Role.THERAPIST, parse("```json\n$json\n```")!!.map["S1"])
        assertEquals(Role.PATIENT, parse("Sure! Here {is} my answer: $json Hope it helps.")!!.map["S0"])
    }

    @Test
    fun missingUnknownOrEqualIdsGiveNull() {
        assertNull(parse("""{"patient":"S1","confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":"S0","confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":"S0","patient":"S7","confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":"S9","patient":"S1","confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":"S0","patient":"S0","confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":0,"patient":1,"confidence":1,"evidence":[]}"""))
        assertNull(parse("""{"therapist":"","patient":"","confidence":1,"evidence":[]}"""))
    }

    @Test
    fun malformedOrEmptyRepliesGiveNull() {
        for (bad in listOf("", "no json here", "{", "}", "}{", "{\"therapist\":\"S0\",", "[\"S0\",\"S1\"]", "{not json}")) assertNull(bad, parse(bad))
    }

    @Test
    fun noSpeakersGivesNull() = assertNull(parse("""{"therapist":"S0","patient":"S1","confidence":1,"evidence":[]}""", emptyList()))

    @Test
    fun idCaseIsForgiven() = assertEquals(Role.THERAPIST, parse("""{"therapist":" s0 ","patient":"S1","confidence":1,"evidence":[]}""")!!.map["S0"])

    @Test
    fun confidenceIsClamped() {
        fun conf(c: String) = parse("""{"therapist":"S0","patient":"S1","confidence":$c,"evidence":[]}""")!!.confidence
        assertEquals(1.0, conf("7"), 0.0)
        assertEquals(0.0, conf("-3"), 0.0)
        assertEquals(0.25, conf("0.25"), 0.0)
        assertEquals(0.5, conf("\"0.5\""), 0.0)
        assertEquals(0.0, conf("\"high\""), 0.0)
        assertEquals(0.0, conf("null"), 0.0)
    }

    @Test
    fun missingConfidenceIsZero() = assertEquals(0.0, parse("""{"therapist":"S0","patient":"S1","evidence":[]}""")!!.confidence, 0.0)

    @Test
    fun evidenceNotInTheInputIsDropped() {
        val g = parse("""{"therapist":"S0","patient":"S1","confidence":1,"evidence":["made up quote","I could not sleep at all","",7,null]}""")!!
        assertEquals(listOf("I could not sleep at all"), g.evidence)
    }

    @Test
    fun evidenceIsMatchedWithoutCaseAndWhitespaceDifferences() {
        val g = parse("""{"therapist":"S0","patient":"S1","confidence":1,"evidence":["  TELL   me\nmore ABOUT that. "]}""")!!
        assertEquals(1, g.evidence.size)
    }

    @Test
    fun atMostThreeDistinctEvidenceItemsAreKept() {
        val ev = listOf("Hello", "how have you been", "Not great", "Tell me more", "Hello").joinToString(",") { "\"$it\"" }
        val g = parse("""{"therapist":"S0","patient":"S1","confidence":1,"evidence":[$ev]}""")!!
        assertEquals(listOf("Hello", "how have you been", "Not great"), g.evidence)
    }

    @Test
    fun evidenceThatIsNotAnArrayIsIgnored() = assertEquals(emptyList<String>(), parse("""{"therapist":"S0","patient":"S1","confidence":1,"evidence":"Hello"}""")!!.evidence)

    @Test
    fun otherSpeakersAreOther() {
        val g = parse("""{"therapist":"S0","patient":"S2","confidence":1,"evidence":[]}""", listOf("S0", "S1", "S2", "S3"))!!
        assertEquals(mapOf("S0" to Role.THERAPIST, "S1" to Role.OTHER, "S2" to Role.PATIENT, "S3" to Role.OTHER), g.map)
    }

    @Test
    fun fallbackIsFirstTherapistSecondPatientRestOther() {
        val g = RoleGuess.fallback(listOf("S1", "S0", "S2", "S1"))
        assertEquals(mapOf("S1" to Role.THERAPIST, "S0" to Role.PATIENT, "S2" to Role.OTHER), g.map)
        assertEquals(0.0, g.confidence, 0.0)
        assertTrue(g.evidence.isEmpty())
    }

    @Test
    fun fallbackWithOneOrNoSpeaker() {
        assertEquals(mapOf("S0" to Role.THERAPIST), RoleGuess.fallback(listOf("S0")).map)
        assertEquals(emptyMap<String, Role>(), RoleGuess.fallback(emptyList()).map)
        assertNotNull(RoleGuess.fallback(emptyList()))
    }
}
