package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceBuilderTest {
    private fun p(s: Long, e: Long, text: String, sp: String? = "S0") = Piece(s, e, text, sp)

    @Test
    fun emptyInputGivesNothing() = assertEquals(emptyList<SegmentRow>(), UtteranceBuilder.build(emptyList()))

    @Test
    fun neighbouringPiecesOfOneSpeakerAreMerged() {
        val u = UtteranceBuilder.build(listOf(p(0, 20_000, "one two"), p(20_000, 30_000, "three"), p(31_000, 40_000, "other", "S1")))
        assertEquals(listOf(SegmentRow(1, 0, 30_000, "one two three", "S0"), SegmentRow(2, 31_000, 40_000, "other", "S1")), u)
    }

    @Test
    fun aSpeakerComingBackAfterAnotherIsANewUtterance() {
        val u = UtteranceBuilder.build(listOf(p(0, 1, "a", "S0"), p(1, 2, "b", "S1"), p(2, 3, "c", "S0")))
        assertEquals(listOf(1, 2, 3), u.map { it.idx })
    }

    @Test
    fun emptyAndBlankTextsAreDroppedAndDoNotSeparateTheNeighbours() {
        val u = UtteranceBuilder.build(listOf(p(0, 1, "a"), p(1, 2, ""), p(2, 3, "   "), p(3, 4, "b")))
        assertEquals(listOf(SegmentRow(1, 0, 4, "a b", "S0")), u)
    }

    @Test
    fun allTextsEmptyGivesNothing() = assertEquals(emptyList<SegmentRow>(), UtteranceBuilder.build(listOf(p(0, 1, " "), p(1, 2, ""))))

    @Test
    fun textsAreTrimmed() = assertEquals("a b", UtteranceBuilder.build(listOf(p(0, 1, "  a "), p(1, 2, "\nb\n"))).single().text)

    @Test
    fun unknownSpeakersAreNotMerged() {
        val u = UtteranceBuilder.build(listOf(p(0, 1, "a", null), p(1, 2, "b", null)))
        assertEquals(listOf(SegmentRow(1, 0, 1, "a"), SegmentRow(2, 1, 2, "b")), u)
    }

    @Test
    fun unlabelledForgetsTheSpeakers() {
        val u = UtteranceBuilder.unlabelled(listOf(p(0, 1, "a", "S0"), p(1, 2, "b", "S0")))
        assertEquals(listOf(null, null), u.map { it.speaker })
        assertEquals(2, u.size)
    }

    @Test
    fun oneSpeakerWholeRecordingIsOneUtterance() {
        val u = UtteranceBuilder.build((0 until 5).map { p(it * 20_000L, (it + 1) * 20_000L, "w$it") })
        assertEquals(SegmentRow(1, 0, 100_000, "w0 w1 w2 w3 w4", "S0"), u.single())
    }
}
