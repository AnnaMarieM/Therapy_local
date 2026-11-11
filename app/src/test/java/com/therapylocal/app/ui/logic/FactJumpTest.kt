package com.therapylocal.app.ui.logic

import com.therapylocal.app.ui.models.FactMention
import com.therapylocal.app.ui.models.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The jump from a fact to its line: which mention opens, where the transcript scrolls to, what is highlighted, and what happens when the lines are gone. */
class FactJumpTest {
    /** Eight 5 s lines; the speaker changes at 4 (a turn boundary between 3 and 4). */
    private fun lines(count: Int = 8, offsetMs: Long = 0) = (0 until count).map {
        TranscriptLine(it, "00:%02d".format(it * 5), "line $it", if (it < 4) "Therapist" else "Patient", it * 5_000L + offsetMs, it * 5_000L + 5_000 + offsetMs)
    }

    private fun mention(from: Int, to: Int = from, session: String = "s1", first: Boolean = true, timed: Boolean = true) =
        FactMention(session, "2026-01-01", first, from, to, if (timed) from * 5_000L else 0, if (timed) to * 5_000L + 5_000 else 0)

    @Test
    fun aSingleMentionOpensItsLineAndHighlightsIt() {
        val t = FactJump.resolve(mention(3), lines())
        assertEquals(TranscriptTarget("s1", 3, 3..3, TargetState.Exact), t)
        assertTrue(t.highlights(3))
        assertFalse(t.highlights(2))
        assertFalse(t.highlights(4))
    }

    @Test
    fun aRangeThatCrossesATurnIsHighlightedWhole() {
        val t = FactJump.resolve(mention(3, 5), lines())
        assertEquals(3..5, t.highlight)
        assertEquals(3, t.scrollTo)
        assertEquals(TargetState.Exact, t.state)
    }

    @Test
    fun aLastSegmentThatIsGoneIsCutToTheLastThereIs() {
        val t = FactJump.resolve(mention(6, 9), lines())
        assertEquals(6..7, t.highlight)
        assertEquals(TargetState.Exact, t.state)
    }

    @Test
    fun severalMentionsOpenTheFirstOccurrenceAndEachOneResolvesInItsOwnSession() {
        val again = mention(2, session = "s2", first = false)
        val ms = listOf(again, mention(5)) // listed out of order: the fact's own occurrence is chosen, not the head
        assertEquals("s1", FactJump.initial(ms)?.sessionId)
        assertEquals(5, FactJump.initial(ms)?.segFrom)
        assertEquals(TranscriptTarget("s2", 2, 2..2, TargetState.Exact), FactJump.resolve(again, lines()))
    }

    @Test
    fun withoutAFirstOccurrenceTheEarliestListedOpens() {
        val ms = listOf(mention(1, session = "s2", first = false), mention(4, session = "s3", first = false))
        assertEquals("s2", FactJump.initial(ms)?.sessionId)
        assertNull(FactJump.initial(emptyList()))
    }

    @Test
    fun missingSegmentsFallBackToTheStoredTimeWithoutAHighlight() {
        // The newer transcript has only five segments: 6 is gone, but its time (30 s) is inside the last one, which is longer.
        val short = lines(4) + TranscriptLine(4, "00:20", "one long line", "Patient", 20_000, 60_000)
        val t = FactJump.resolve(mention(6), short)
        assertEquals(TargetState.Moved, t.state)
        assertEquals(4, t.scrollTo)
        assertTrue(t.highlight.isEmpty())
        assertFalse(t.highlights(4))
    }

    @Test
    fun aSupersededTranscriptWithOtherTimesUnderTheSameNumbersIsNotHighlighted() {
        // Re-transcribed: segment 3 exists but starts 2 s later, so it is not the line the fact was made from.
        val t = FactJump.resolve(mention(3), lines(offsetMs = 2_000))
        assertEquals(TargetState.Moved, t.state)
        assertTrue(t.highlight.isEmpty())
        assertEquals(2, t.scrollTo) // 15 s falls in segment 2 (2 s..7 s later than before: 12..17 s)
    }

    @Test
    fun withNoStoredTimeTheNumbersAreTrusted() {
        val t = FactJump.resolve(mention(3, timed = false), lines(offsetMs = 2_000))
        assertEquals(TargetState.Exact, t.state)
        assertEquals(3..3, t.highlight)
    }

    @Test
    fun nothingToGoByLeavesTheTranscriptAtTheTop() {
        assertEquals(TargetState.Missing, FactJump.resolve(mention(9, timed = false), lines()).state)
        assertNull(FactJump.resolve(mention(9, timed = false), lines()).scrollTo)
        assertEquals(TargetState.Missing, FactJump.resolve(mention(3), emptyList()).state)
        // A time beyond the end of the transcript.
        assertEquals(TargetState.Missing, FactJump.resolve(mention(30), lines()).state)
    }

    @Test
    fun theListRowIsTheLineAfterTheHeader() {
        val ls = lines()
        val t = FactJump.resolve(mention(3), ls)
        assertEquals(5, FactJump.listIndex(t, ls, headerItems = 2))
        assertNull(FactJump.listIndex(FactJump.resolve(mention(9, timed = false), ls), ls, 2))
        // Segment numbers need not start at 0 or be dense: the position in the list is what counts.
        val sparse = listOf(ls[2], ls[5], ls[7])
        assertEquals(3, FactJump.listIndex(TranscriptTarget("s1", 5, 5..5, TargetState.Exact), sparse, 2))
    }
}
