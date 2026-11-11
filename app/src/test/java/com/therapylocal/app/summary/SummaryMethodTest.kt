package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.SegmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-transcript method: one call for a short transcript, windows then merge levels then the last call for a long one. */
class SummaryMethodTest {
    /** [n] utterances of five words each, "Patient: wN a b c d". */
    private fun long(n: Int) = (1..n).map { SegmentRow(it, it * 1000L, it * 1000L + 900, "Patient: w$it a b c", "S0") }

    @Test
    fun aTranscriptWithNoWordsHasNoSummary() {
        assertNull(MapReduceSummary().summarize(emptyList(), testPrompts(), RecordingAsk()))
        assertNull(MapReduceSummary().summarize(listOf(SegmentRow(1, 0, 1, "   ", "S0")), testPrompts(), RecordingAsk()))
    }

    @Test
    fun aShortTranscriptIsOneCallWithTheFinalPrompts() {
        val ask = RecordingAsk()
        val out = MapReduceSummary().summarize(shortSession(), testPrompts(), ask)!!
        assertEquals(1, out.calls)
        val c = ask.calls.single()
        assertEquals("FINAL-SYSTEM\nformat", c.system)
        assertTrue(c.user.startsWith("FINAL-USER "))
        assertEquals(SummaryConfig.FINAL_MAX_OUTPUT_TOKENS, c.maxOut)
        assertTrue("How was your week?" in c.user)
    }

    @Test
    fun aLongTranscriptIsCondensedWindowByWindowAndThenSummarisedFromTheNotes() {
        val ask = RecordingAsk()
        val segments = long(60) // 300 words
        // 14 tokens = 10 words per window, no overlap, and the notes of all windows fit one final call.
        val method = MapReduceSummary(windowTokens = 14, overlapTokens = 0, reduceTokens = 100_000)
        val out = method.summarize(segments, testPrompts(), ask)!!
        val windows = ask.calls.filter { it.system.startsWith("NOTES") }
        assertEquals(30, windows.size)
        assertEquals(31, out.calls)
        assertTrue(windows.all { it.maxOut == SummaryConfig.NOTES_MAX_OUTPUT_TOKENS })
        // Every word of the transcript reached some window.
        val seen = windows.joinToString(" ") { it.user }
        for (s in segments) assertTrue(s.text, s.text.split(" ").all { w -> w in seen.split(Regex("\\s+")) })
        // The part numbers are given to the model.
        assertTrue(windows.first().user.startsWith("NOTES-USER 1/30"))
        assertTrue(windows.last().user.startsWith("NOTES-USER 30/30"))
        // The last call gets the notes, in order, not the transcript.
        val last = ask.calls.last()
        assertEquals("FINAL-SYSTEM\nformat", last.system)
        assertTrue(last.user.indexOf("notes 1") in 0 until last.user.indexOf("notes 2"))
        assertTrue("w1" !in last.user)
    }

    @Test
    fun notesThatAreStillTooLongAreMergedInGroupsBeforeTheLastCall() {
        val notes = { i: Int -> List(40) { "n${i}_$it" }.joinToString(" ") } // 40 words per note
        val ask = RecordingAsk(notes = notes)
        // 10 words per window; 300 words give 30 windows and 1,200 words of notes; the merge budget is 100 words (140 tokens).
        val method = MapReduceSummary(windowTokens = 14, overlapTokens = 0, reduceTokens = 140)
        val out = method.summarize(long(60), testPrompts(), ask)!!
        val notesCalls = ask.calls.filter { it.system.startsWith("NOTES") }
        assertTrue("merge calls happened: ${notesCalls.size}", notesCalls.size > 30)
        // The last call fits the budget (the merges cut it down or it was trimmed to it).
        assertTrue(MapReduceSummary.wordCount(ask.calls.last().user) <= 100 + 3)
        assertEquals(notesCalls.size + 1, out.calls)
    }

    @Test
    fun progressIsReportedAndEndsComplete() {
        val seen = ArrayList<Pair<Int, Int>>()
        MapReduceSummary(windowTokens = 14, overlapTokens = 0, reduceTokens = 100_000).summarize(long(6), testPrompts(), RecordingAsk()) { d, t -> seen += d to t }
        assertEquals(0, seen.first().first)
        assertEquals(seen.last().second, seen.last().first)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun theMethodIdChangesWithItsTuning() {
        assertTrue(MapReduceSummary(windowTokens = 1000).id != MapReduceSummary(windowTokens = 2000).id)
    }

    @Test
    fun promptsGetTheFormatAndLimitsFromTheOnePlace() {
        val loaded = SummaryPrompts.load({ name -> "$name|{{FORMAT}}|{{MAX_WORDS}}|{{MAX_SENTENCES}}|{{NOTES_WORDS}}|{{TEXT}}" }, "en")
        for (s in SummaryConfig.SECTIONS) assertTrue(s.label in loaded.finalSystem)
        assertTrue("|${SummaryConfig.MAX_WORDS_PER_SECTION}|${SummaryConfig.MAX_SENTENCES_PER_SECTION}|" in loaded.finalSystem)
        assertTrue("|${SummaryConfig.NOTES_MAX_WORDS}|" in loaded.notesSystem)
        // The user templates keep their placeholders for the method to fill.
        assertTrue("{{TEXT}}" in loaded.finalUser && "{{TEXT}}" in loaded.notesUser)
        assertTrue(loaded.finalSystem.startsWith(SummaryConfig.PROMPT_FINAL_SYSTEM))
    }

    @Test
    fun czechPromptsAreReadFromTheCsFolder() {
        val asked = ArrayList<String>()
        SummaryPrompts.load({ asked += it; "x" }, "cs")
        assertEquals(setOf("cs/summary.system.txt", "cs/summary.user.txt", "cs/summary.notes.system.txt", "cs/summary.notes.user.txt"), asked.toSet())
    }

    @Test
    fun theVersionChangesWithAnyPromptText() {
        val a = SummaryPrompts("a", "b", "c", "d").version()
        assertEquals(a, SummaryPrompts("a", "b", "c", "d").version())
        assertTrue(a != SummaryPrompts("a", "b", "c", "e").version())
        assertTrue(a != SummaryPrompts("x", "b", "c", "d").version())
    }

    @Test
    fun theContextCountCoversTheLargestCall() {
        val need = MapReduceSummary.requiredContext(testPrompts())
        assertTrue(need > SummaryConfig.FINAL_MAX_OUTPUT_TOKENS + SummaryConfig.WINDOW_TOKENS)
    }
}
