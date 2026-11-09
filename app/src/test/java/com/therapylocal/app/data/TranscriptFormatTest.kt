package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatTest {
    private fun seg(i: Int, startMs: Long, text: String, sp: String?) = SegmentRow(i, startMs, startMs + 1000, text, sp)

    @Test
    fun blocksAreTimeLineThenLabelAndTextSeparatedByABlankLine() {
        val segs = listOf(seg(1, 192_000, "Hello.", "S0"), seg(2, 200_500, "Hi there.", "S1"))
        val out = TranscriptFormat.format(segs, TranscriptFormat.neutralLabels(segs))
        assertEquals("[00:03:12]\nSpeaker 1: Hello.\n\n[00:03:20]\nSpeaker 2: Hi there.", out)
    }

    @Test
    fun emptyTranscriptIsEmptyText() = assertEquals("", TranscriptFormat.format(emptyList()) { "x" })

    @Test
    fun blankUtterancesAreSkipped() = assertEquals("[00:00:00]\nA: x", TranscriptFormat.format(listOf(seg(1, 0, "x", "S0"), seg(2, 5, " ", "S0"))) { "A" })

    @Test
    fun hoursPastNinetyNineGrowTheField() {
        assertEquals("100:00:01", TranscriptFormat.clock(360_001_000L))
        assertEquals("00:00:00", TranscriptFormat.clock(999))
        assertEquals("00:00:00", TranscriptFormat.clock(-5))
        assertEquals("01:01:01", TranscriptFormat.clock(3_661_000))
    }

    @Test
    fun neutralLabelsFollowFirstAppearanceNotTheId() {
        val segs = listOf(seg(1, 0, "a", "S1"), seg(2, 1, "b", "S0"), seg(3, 2, "c", "S1"), seg(4, 3, "d", null))
        val label = TranscriptFormat.neutralLabels(segs)
        assertEquals("Speaker 1", label("S1"))
        assertEquals("Speaker 2", label("S0"))
        assertEquals("Speaker ?", label(null))
        assertEquals("Speaker ?", label("S9"))
    }

    @Test
    fun roleLabelUsesRoleNamesForTherapistAndPatientOnly() {
        val segs = listOf(seg(1, 0, "a", "S0"), seg(2, 1, "b", "S1"), seg(3, 2, "c", "S2"), seg(4, 3, "d", null))
        val map = mapOf("S0" to Role.PATIENT, "S1" to Role.THERAPIST, "S2" to Role.OTHER)
        val label = TranscriptFormat.roleLabel(map, { if (it == Role.THERAPIST) "Therapist" else "Patient" }, TranscriptFormat.neutralLabels(segs))
        assertEquals(listOf("Patient", "Therapist", "Speaker 3", "Speaker ?"), segs.map { label(it.speaker) })
    }

    @Test
    fun roleLabelFallsBackToNeutralWhenThereIsNoMap() {
        val label = TranscriptFormat.roleLabel(emptyMap(), { "role" }) { "neutral ${it}" }
        assertEquals("neutral S0", label("S0"))
    }
}
