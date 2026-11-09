package com.therapylocal.app.jobs

import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The decision of the ROLES job: who is asked, how often, and what is stored when the answer is no good. */
class RoleStepTest {
    private val segments = listOf(
        SegmentRow(1, 0, 4_000, "How was your week?", "S1"),
        SegmentRow(2, 4_000, 9_000, "Hard, I could not sleep at all.", "S0"),
        SegmentRow(3, 9_000, 12_000, "Tell me more about the nights.", "S1"),
    )
    private val valid = """{"therapist":"S1","patient":"S0","confidence":0.9,"evidence":["How was your week?","not in the text"]}"""

    @Test
    fun aValidReplyIsKeptWithItsGroundedEvidence() {
        val asked = ArrayList<String>()
        val d = RoleStep.decide(segments, hasMap = false) { asked += it; valid }!!
        assertEquals(RoleStep.LLM, d.source)
        assertEquals(1, d.attempts)
        assertEquals(Role.THERAPIST, d.guess.map["S1"])
        assertEquals(Role.PATIENT, d.guess.map["S0"])
        assertEquals(listOf("How was your week?"), d.guess.evidence)
        assertEquals(listOf("S1", "S0"), d.speakers) // order of first appearance
        // The model sees the neutral ids, not roles.
        assertEquals(true, "S1: How was your week?" in asked.single())
    }

    @Test
    fun anInvalidReplyIsAskedForOnceMore() {
        val replies = ArrayDeque(listOf("I think it is the first one", valid))
        val d = RoleStep.decide(segments, false) { replies.removeFirst() }!!
        assertEquals(RoleStep.LLM, d.source)
        assertEquals(2, d.attempts)
    }

    @Test
    fun aCallThatThrowsCountsAsAnAttempt() {
        val replies = ArrayDeque<() -> String>(listOf({ throw IllegalStateException("x") }, { valid }))
        val d = RoleStep.decide(segments, false) { replies.removeFirst()() }!!
        assertEquals(RoleStep.LLM, d.source)
        assertEquals(2, d.attempts)
    }

    @Test
    fun twoInvalidRepliesGiveTheFallbackMap() {
        var calls = 0
        val d = RoleStep.decide(segments, false) { calls++; """{"therapist":"S7","patient":"S0"}""" }!!
        assertEquals(2, calls)
        assertEquals(RoleStep.FALLBACK, d.source)
        assertEquals(0.0, d.guess.confidence, 0.0)
        assertEquals(emptyList<String>(), d.guess.evidence)
        // The first speaker to talk is taken as the therapist.
        assertEquals(Role.THERAPIST, d.guess.map["S1"])
        assertEquals(Role.PATIENT, d.guess.map["S0"])
    }

    @Test
    fun oneSpeakerIsSkippedWithoutAskingTheModel() {
        val one = segments.map { it.copy(speaker = "S0") }
        assertNull(RoleStep.decide(one, false) { fail("must not ask"); "" })
        assertNull(RoleStep.decide(segments.map { it.copy(speaker = null) }, false) { fail("must not ask"); "" })
    }

    @Test
    fun anExistingMapIsKeptWithoutAskingTheModel() {
        assertNull(RoleStep.decide(segments, hasMap = true) { fail("must not ask"); "" })
    }

    @Test
    fun aThirdSpeakerIsOther() {
        val three = segments + SegmentRow(4, 12_000, 13_000, "Hello", "S2")
        val d = RoleStep.decide(three, false) { valid }!!
        assertNotNull(d)
        assertEquals(Role.OTHER, d.guess.map["S2"])
    }

    @Test
    fun aStopPropagates() {
        try {
            RoleStep.decide(segments, false) { throw JobStopped() }
            fail("should propagate")
        } catch (_: JobStopped) {
        }
    }
}
