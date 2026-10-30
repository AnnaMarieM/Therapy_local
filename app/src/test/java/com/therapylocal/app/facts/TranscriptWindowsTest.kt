package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class TranscriptWindowsTest {
    private fun text(n: Int, sep: String = " ") = (1..n).joinToString(sep) { "w$it" }
    private fun toks(s: String) = s.split(" ")

    /** One segment holding the whole text: the windows are then the plain word windows. */
    private fun split(text: String, w: Int, o: Int) = TranscriptWindows.bySegments(listOf(SegmentRow(1, 0, 0, text)), w, o).map { it.text }

    /** Window count: one window if n <= W, otherwise 1 + ceil((n - W) / (W - O)). */
    private fun expectedCount(n: Int, w: Int, o: Int) = if (n <= w) 1 else 1 + (n - w + (w - o) - 1) / (w - o)

    @Test
    fun randomParametersSatisfyAllWindowProperties() {
        val rnd = Random(1234)
        repeat(1500) {
            val n = 1 + rnd.nextInt(400)
            val w = 1 + rnd.nextInt(60)
            val o = rnd.nextInt(w)
            val win = split(text(n), w, o)
            val ctx = "n=$n w=$w o=$o"
            assertEquals(ctx, expectedCount(n, w, o), win.size)
            assertTrue(ctx, win.none { it.isBlank() })
            assertTrue(ctx, win.all { toks(it).size <= w })
            // Coverage: every input word appears in some window.
            assertEquals(ctx, (1..n).map { "w$it" }.toSet(), win.flatMap { toks(it) }.toSet())
            assertEquals(ctx, "w1", toks(win.first()).first())
            assertEquals(ctx, "w$n", toks(win.last()).last())
            for (i in 1 until win.size) {
                val prev = toks(win[i - 1])
                val cur = toks(win[i])
                // Every window except the last is full; the last holds whatever remains and may be short.
                assertEquals(ctx, w, prev.size)
                // Consecutive windows share exactly the requested overlap, in order.
                assertEquals(ctx, prev.takeLast(o), cur.take(o))
                // The window start advances by exactly w - o (so no extra shared words).
                assertEquals(ctx, w - o, cur.first().substring(1).toInt() - prev.first().substring(1).toInt())
            }
            // The last window always contributes at least one new word.
            if (win.size > 1) assertTrue(ctx, toks(win.last()).size > o)
        }
    }

    @Test
    fun boundarySizes() {
        assertEquals(listOf(text(10)), split(text(10), 10, 3)) // n == window
        assertEquals(listOf(text(10), "w8 w9 w10 w11"), split(text(11), 10, 3)) // n == window + 1
        assertEquals(listOf("w1 w2 w3", "w2 w3 w4", "w3 w4 w5"), split(text(5), 3, 2)) // overlap == window - 1
        assertEquals(listOf("w1 w2 w3", "w3 w4 w5"), split(text(5), 3, 1))
        assertEquals(listOf("w1 w2", "w3 w4", "w5"), split(text(5), 2, 0))
        assertEquals(listOf("w1"), split("w1", 1, 0))
        assertEquals(listOf("w1", "w2", "w3"), split(text(3), 1, 0))
    }

    @Test
    fun overlapNotBelowWindowIsRejectedImmediatelyAndNeverLoops() {
        for ((w, o) in listOf(5 to 5, 5 to 6, 1 to 1, 0 to 0, 3 to -1, 5 to 100)) {
            try {
                split(text(50), w, o)
                fail("expected rejection for window=$w overlap=$o")
            } catch (_: IllegalArgumentException) {
            }
        }
        // Rejected even when the text is short enough that it would otherwise be one window.
        try {
            split("a b", 5, 5)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun whitespaceVariantsAreNormalised() {
        val messy = "  w1\tw2\n\nw3   w4\r\nw5 w6\t\n"
        assertEquals(listOf("w1 w2 w3", "w3 w4 w5", "w5 w6"), split(messy, 3, 1))
        for (sep in listOf("\t", "\n", "   ", "\r\n", " \n\t ")) {
            assertEquals(split(text(25), 7, 2), split(text(25, sep), 7, 2))
        }
    }

    @Test
    fun blankInputHasNoWindow() {
        assertEquals(emptyList<String>(), split("", 10, 2))
        assertEquals(emptyList<String>(), split(" \n\t ", 10, 2))
        assertEquals(emptyList<TranscriptWindows.Span>(), TranscriptWindows.bySegments(emptyList(), 10, 2))
    }

    @Test
    fun windowsSpanTheSegmentsOfTheirFirstAndLastWord() {
        // Segments of 3, 1, 4 and 2 words: w1-w3 | w4 | w5-w8 | w9-w10.
        val segs = listOf(SegmentRow(1, 0, 0, "w1 w2 w3"), SegmentRow(2, 0, 0, "w4"), SegmentRow(3, 0, 0, "w5 w6 w7 w8"), SegmentRow(4, 0, 0, "  w9\tw10 "))
        val win = TranscriptWindows.bySegments(segs, 4, 1)
        assertEquals(listOf("w1 w2 w3 w4", "w4 w5 w6 w7", "w7 w8 w9 w10"), win.map { it.text })
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4), win.map { it.segFrom to it.segTo })
        // A window inside one segment spans just that segment; the windows of the joined text are the same words.
        assertEquals(listOf(3 to 3), TranscriptWindows.bySegments(listOf(segs[2]), 10, 2).map { it.segFrom to it.segTo })
        assertEquals(split(text(10), 4, 1), win.map { it.text })
    }

    @Test
    fun tokensConvertAtOnePointFourTokensPerWord() {
        assertEquals(1.4, TranscriptWindows.TOKENS_PER_WORD, 0.0)
        assertEquals(714, TranscriptWindows.wordsFor(1000))
        assertEquals(35, TranscriptWindows.wordsFor(50))
        assertEquals(0, TranscriptWindows.wordsFor(1))
    }

    @Test
    fun stripSpeakerLabels() {
        fun s(x: String) = TranscriptWindows.stripSpeakerLabels(x)
        assertEquals("hello there\nfine", s("Therapist: hello there\nClient: fine"))
        assertEquals("hi", s("   Dr.: hi"))
        assertEquals("hi", s("**Therapist:** hi"))
        assertEquals("hi", s("Therapist:hi"))
        // Mid-line colons and unlabeled lines are content, not labels.
        assertEquals("I said this: that", s("Client: I said this: that"))
        assertEquals("the time was 10:30", s("the time was 10:30"))
        assertEquals("lowercase: stays", s("lowercase: stays"))
        assertEquals("Note to self: stays", s("Note to self: stays")) // a label with spaces is not a speaker label
        // The label pattern is letters and dots only, so a digit in the name means it is not recognised and the line is kept as is.
        assertEquals("Client2: hi", s("Client2: hi"))
        assertEquals("Speaker 1: hi", s("Speaker 1: hi"))
        assertEquals("", s(""))
        assertEquals("a\n\nb", s("Client: a\n\nTherapist: b"))
        // Windows newlines are accepted and normalised to LF.
        assertEquals("a\nb", s("Client: a\r\nTherapist: b"))
        // Only the leading label of a line is removed.
        assertEquals("Therapist: x", s("Client: Therapist: x"))
    }
}
