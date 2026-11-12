package com.therapylocal.app.facts

import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extraction reads a role-labelled dialogue cut on turn boundaries; quotes are still located in the segment texts, not in the labels. */
class ExtractionDialogueTest {
    private fun seg(idx: Int, speaker: String, text: String) = SegmentRow(idx, idx * 10_000L, idx * 10_000L + 9_000, text, speaker)

    private val dialogue = listOf(
        seg(1, "S0", "Is it since the spring that you cannot sleep?"),
        seg(2, "S1", "Yes, since March, when my brother Tom lost his job, and since then I send him money every month."),
        seg(3, "S0", "That sounds like a lot to carry."),
        seg(4, "S1", "It is. I also stopped running."),
    )
    private val map = SpeakerMaps.encode(mapOf("S0" to Role.THERAPIST, "S1" to Role.PATIENT))
    private val swapped = SpeakerMaps.encode(SpeakerMaps.swap(SpeakerMaps.decode(map)))

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    @Test
    fun windowsAreTheLabelledDialogueInTheFormatTheScreenUses() {
        val w = ExtractionDialogue.windows(dialogue, map, 1000, 0).single()
        assertEquals(1 to 4, w.segFrom to w.segTo)
        assertTrue(w.text.startsWith("[00:00:10]\nTherapist: Is it since the spring"))
        assertTrue(w.text.contains("[00:00:20]\nPatient: Yes, since March"))
        assertFalse(w.text.contains("Speaker"))
    }

    @Test
    fun withoutAMapEveryoneKeepsANeutralNumberedLabel() {
        val w = ExtractionDialogue.windows(dialogue, null, 1000, 0).single().text
        assertTrue(w.contains("Speaker 1: Is it since") && w.contains("Speaker 2: Yes, since March"))
    }

    @Test
    fun aRolesSwapChangesTheLabelsOfTheSameSegments() {
        val a = ExtractionDialogue.windows(dialogue, map, 1000, 0).single().text
        val b = ExtractionDialogue.windows(dialogue, swapped, 1000, 0).single().text
        assertNotEquals(a, b)
        assertTrue(b.contains("Patient: Is it since the spring"))
        assertTrue(b.contains("Therapist: Yes, since March"))
    }

    @Test
    fun windowsAreCutOnTurnBoundariesAndNeverExceedTheBudget() {
        val long = (1..12).map { seg(it, if (it % 2 == 1) "S0" else "S1", (1..9).joinToString(" ") { w -> "w${it}x$w" }) }
        val wins = ExtractionDialogue.windows(long, map, 40, 14)
        assertTrue(wins.size > 1)
        for (w in wins) {
            assertTrue("window of ${words(w.text)} words", words(w.text) <= 40)
            // Every block in the window is a whole turn: it starts with its time line and the text ends the block.
            val blocks = w.text.split("\n\n")
            assertTrue(blocks.all { it.startsWith("[") && it.lines()[1].matches(Regex("(Therapist|Patient): .*")) })
        }
        // Every turn is in some window, in whole.
        val all = wins.joinToString("\n") { it.text }
        long.forEach { assertTrue(all.contains(it.text)) }
        assertEquals(1, wins.first().segFrom)
        assertEquals(12, wins.last().segTo)
    }

    @Test
    fun aLaterWindowStartsWithTheTurnsBeforeItsFirstNewOneSoAnAnswerHasItsQuestion() {
        val turns = (1..10).map { seg(it, if (it % 2 == 1) "S0" else "S1", "turn$it " + (1..7).joinToString(" ") { w -> "pad$w" }) }
        // Each turn is 10 words with its header; a window holds 3 turns, the overlap one.
        val wins = ExtractionDialogue.windows(turns, map, 35, 12)
        assertTrue(wins.size >= 3)
        for (i in 1 until wins.size) {
            val prevLast = wins[i - 1].segTo
            // The window begins at or before the last turn of the previous one: context first, then new turns.
            assertTrue("window $i starts at ${wins[i].segFrom}, previous ended $prevLast", wins[i].segFrom <= prevLast)
            assertTrue(wins[i].segTo > prevLast)
        }
        // The window that first contains a patient turn also contains the therapist turn before it.
        for (w in wins) {
            val firstPatient = w.text.indexOf("Patient:")
            if (firstPatient >= 0 && w.segFrom > 1) assertTrue("the first turn of window ${w.segFrom} is ${w.text.take(30)}", w.text.contains("Therapist:") && w.text.indexOf("Therapist:") < firstPatient)
        }
    }

    @Test
    fun aTurnLongerThanAWindowIsCutByWordsAndEveryPieceKeepsItsHeader() {
        val monologue = listOf(seg(1, "S1", (1..100).joinToString(" ") { "m$it" }))
        val wins = ExtractionDialogue.windows(monologue, map, 30, 5)
        assertTrue(wins.size >= 4)
        assertTrue(wins.all { it.text.startsWith("[00:00:10]\nPatient: ") && words(it.text) <= 30 && it.segFrom == 1 && it.segTo == 1 })
        assertEquals((1..100).map { "m$it" }, wins.flatMap { w -> w.text.substringAfter("Patient: ").split(" ") }.distinct().sortedBy { it.substring(1).toInt() })
    }

    @Test
    fun blankUtterancesAreLeftOutAndAnEmptyTranscriptHasNoWindow() {
        assertTrue(ExtractionDialogue.windows(emptyList(), map, 100, 10).isEmpty())
        assertTrue(ExtractionDialogue.windows(listOf(seg(1, "S0", "  ")), map, 100, 10).isEmpty())
    }

    @Test
    fun aQuoteStillResolvesToItsSegmentsBecauseItIsMatchedAgainstTheSegmentTextNotTheLabels() {
        val w = ExtractionDialogue.windows(dialogue, map, 1000, 0).single()
        val ground = ExtractionDialogue.groundingText(dialogue, w.segFrom, w.segTo)
        assertFalse(ground.contains("Patient") || ground.contains("[00:"))
        val quote = Fact("Sends brother money", "Marek sends his brother money every month.", "since then I send him money every month")
        assertTrue(FactParser.evidenceHolds(quote, ground))
        assertEquals(2 to 2, FactParser.segmentSpan(quote.evidence, dialogue))
        // A quote of a label or a time is not grounded.
        assertFalse(FactParser.evidenceHolds(quote.copy(evidence = "Patient: Yes since March"), ground))
        assertFalse(FactParser.evidenceHolds(quote.copy(evidence = "00 00 20 Patient"), ground))
        // The span and the speaker of the fact still come from the segments.
        assertEquals(FactSpan(2, 2, 20_000, 29_000, "S1"), FactSpans.of(2, 2, dialogue))
    }

    @Test
    fun theCacheKeyHoldsThePromptModelMapVersionAndText() {
        val k = ExtractionDialogue.cacheKey("p1", "gemma", 3, "text")
        assertTrue(k.startsWith("p1|gemma|3|"))
        assertNotEquals(k, ExtractionDialogue.cacheKey("p1", "gemma", 4, "text"))
        assertNotEquals(k, ExtractionDialogue.cacheKey("p2", "gemma", 3, "text"))
        assertNotEquals(k, ExtractionDialogue.cacheKey("p1", "gemma", 3, "text2"))
        assertEquals(k, ExtractionDialogue.cacheKey("p1", "gemma", 3, "text"))
        assertEquals(listOf("p1", "gemma", "3"), k.split('|').take(3))
    }
}
