package com.therapylocal.app.summary

import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.facts.PromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-transcript method (tree summarisation): one call for a short transcript, chunks then group levels then the last call for a long one. */
class SummaryMethodTest {
    /** [n] utterances of five words each, "Patient: wN a b c d". */
    private fun long(n: Int) = (1..n).map { SegmentRow(it, it * 1000L, it * 1000L + 900, "Patient: w$it a b c", "S0") }

    /** The tokens one call of [system]/[user] needs, as the method counts them. */
    private fun need(c: RecordingAsk.Call, maxOut: Int) = PromptBudget.required(c.system, c.user, maxOut)

    /** A context just big enough for about [words] plain words of text in the final call (the larger reply cap), so tests need no huge transcripts. */
    private fun ctxFor(words: Int): Int = PromptBudget.required("FINAL-SYSTEM\nformat", "FINAL-USER " + "abcd ".repeat(words), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS)

    @Test
    fun aTranscriptWithNoWordsHasNoSummary() {
        assertNull(TreeSummary().summarize(emptyList(), testPrompts(), RecordingAsk()))
        assertNull(TreeSummary().summarize(listOf(SegmentRow(1, 0, 1, "   ", "S0")), testPrompts(), RecordingAsk()))
    }

    @Test
    fun aShortTranscriptIsOneCallWithTheFinalPrompts() {
        val ask = RecordingAsk()
        val out = TreeSummary().summarize(shortSession(), testPrompts(), ask)!!
        assertEquals(1, out.calls)
        assertEquals(1, out.levels)
        val c = ask.calls.single()
        assertEquals("FINAL-SYSTEM\nformat", c.system)
        assertTrue(c.user.startsWith("FINAL-USER "))
        assertEquals(SummaryConfig.FINAL_MAX_OUTPUT_TOKENS, c.maxOut)
        assertTrue("How was your week?" in c.user)
    }

    @Test
    fun theBudgetIsTheContextTheAppLoadsForItsGemmaJobs() {
        assertEquals(MemoryConfig.LLM_MAX_TOKENS, SummaryConfig.CONTEXT_TOKENS)
        assertEquals(MemoryConfig.LLM_MAX_TOKENS, TreeSummary.requiredContext())
        assertTrue(TreeSummary().id.startsWith("tree-v1"))
    }

    @Test
    fun aLongTranscriptIsChunkedThenSummarisedUpTheTreeAndTheLastCallIsTheFinalPrompt() {
        val ask = RecordingAsk()
        val segments = long(300) // 1,500 words
        val ctx = ctxFor(60)
        val out = TreeSummary(ctx).summarize(segments, testPrompts(), ask)!!
        val notes = ask.calls.filter { it.system.startsWith("NOTES") }
        val finals = ask.calls.filter { !it.system.startsWith("NOTES") }
        assertEquals(1, finals.size)
        assertEquals(ask.calls.last(), finals.single())
        assertEquals(notes.size + 1, out.calls)
        assertTrue("several chunks: ${notes.size}", notes.size > 5)
        assertTrue(out.levels in 2..8)
        // No call is over the budget, and every notes call has the notes reply cap.
        assertTrue(notes.all { need(it, SummaryConfig.NOTES_MAX_OUTPUT_TOKENS) <= ctx && it.maxOut == SummaryConfig.NOTES_MAX_OUTPUT_TOKENS })
        assertTrue(need(finals.single(), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS) <= ctx)
        // Every word of the transcript reached a level-0 chunk, in order, with its role.
        val level0 = notes.takeWhile { "w" in it.user.substringAfter("/") }
        val seen = level0.joinToString(" ") { it.user }.split(Regex("\\s+"))
        for (s in segments) assertTrue(s.text, s.text.split(" ").all { w -> w in seen })
        val order = Regex("w(\\d+) ").findAll(level0.joinToString(" ") { it.user }).map { it.groupValues[1].toInt() }.toList()
        assertEquals(order.sorted(), order)
        assertTrue(level0.first().user.contains("Patient: w1 "))
        // Chunks are filled: all but the last chunk could not take the next utterance.
        assertTrue(level0.dropLast(1).all { need(it, SummaryConfig.NOTES_MAX_OUTPUT_TOKENS) > ctx - 200 })
        // The part numbers are given to the model.
        assertTrue(notes.first().user.startsWith("NOTES-USER 1/"))
    }

    @Test
    fun summariesAreGroupedConsecutivelyUntilTheyFitTheFinalCall() {
        val ask = RecordingAsk(notes = { i -> List(60) { "n${i}_$it" }.joinToString(" ") })
        val ctx = ctxFor(150)
        val out = TreeSummary(ctx).summarize(long(300), testPrompts(), ask)!!
        val notes = ask.calls.filter { it.system.startsWith("NOTES") }
        // Level 0 has k chunks (the user texts naming w..); the rest are merges of notes (n..).
        val merges = notes.filter { "n1_0" in it.user || Regex("n\\d+_0").containsMatchIn(it.user) }
        assertTrue("merge calls happened", merges.isNotEmpty())
        // A merge group holds consecutive notes: their numbers are a run.
        for (m in merges) {
            val ns = Regex("n(\\d+)_0").findAll(m.user).map { it.groupValues[1].toInt() }.toList()
            assertEquals((ns.first()..ns.last()).toList(), ns)
            assertTrue(need(m, SummaryConfig.NOTES_MAX_OUTPUT_TOKENS) <= ctx)
        }
        assertTrue(need(ask.calls.last(), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS) <= ctx)
        assertEquals(notes.size + 1, out.calls)
    }

    @Test
    fun theFinalCallGetsSummariesNotTheTranscript() {
        val ask = RecordingAsk()
        TreeSummary(ctxFor(150)).summarize(long(300), testPrompts(), ask)
        val last = ask.calls.last()
        assertTrue("notes " in last.user && "w1 " !in last.user)
    }

    @Test
    fun theTreeTerminatesWithABoundedDepthAndCallCount() {
        val ask = RecordingAsk()
        val out = TreeSummary(ctxFor(40)).summarize(long(2000), testPrompts(), ask)!! // 10,000 words, tiny budget
        assertTrue(out.levels <= 8 + 1)
        assertTrue("calls ${out.calls}", out.calls < 2000)
        assertEquals(1, ask.calls.count { !it.system.startsWith("NOTES") })
    }

    @Test
    fun aSummaryLargerThanTheBudgetIsSplitNotDropped() {
        // Every summary is 300 words, far over what one call holds (about 40 words): the pieces are split at words and the counts are returned.
        val ask = RecordingAsk(notes = { _ -> List(300) { "big$it" }.joinToString(" ") })
        val out = TreeSummary(ctxFor(40)).summarize(long(40), testPrompts(), ask)!!
        assertTrue("forced splits counted: ${out.forcedSplits}", out.forcedSplits > 0)
        val notes = ask.calls.filter { it.system.startsWith("NOTES") }
        assertTrue(notes.all { need(it, SummaryConfig.NOTES_MAX_OUTPUT_TOKENS) <= ctxFor(40) })
        assertEquals(1, ask.calls.count { !it.system.startsWith("NOTES") })
        assertTrue(need(ask.calls.last(), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS) <= ctxFor(40) + SummaryConfig.FINAL_MAX_OUTPUT_TOKENS - SummaryConfig.NOTES_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun oneUtteranceLongerThanACallIsSplitAtWordsAndNoWordIsLost() {
        val words = List(400) { "x$it" }
        val ask = RecordingAsk()
        val out = TreeSummary(ctxFor(60)).summarize(listOf(SegmentRow(1, 0, 1, "Patient: " + words.joinToString(" "), "S0")), testPrompts(), ask)!!
        assertTrue(out.forcedSplits >= 1)
        val seen = ask.calls.filter { it.system.startsWith("NOTES") }.joinToString(" ") { it.user }.split(Regex("\\s+")).toSet()
        assertTrue(words.all { it in seen })
    }

    @Test
    fun progressIsReportedAndEndsComplete() {
        val seen = ArrayList<Pair<Int, Int>>()
        TreeSummary(ctxFor(40)).summarize(long(100), testPrompts(), RecordingAsk()) { d, t -> seen += d to t }
        assertEquals(0, seen.first().first)
        assertEquals(seen.last().second, seen.last().first)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun theMethodIdChangesWithTheBudget() {
        assertTrue(TreeSummary(4096).id != TreeSummary(6144).id)
    }

    @Test
    fun promptsGetTheFormatAndLimitsFromTheOnePlace() {
        val loaded = SummaryPrompts.load({ name -> "$name|{{FORMAT}}|{{MAX_WORDS}}|{{MAX_SENTENCES}}|{{NOTES_WORDS}}|{{TEXT}}" }, "en")
        for (s in SummaryConfig.format(SummaryConfig.DEFAULT_FORMAT_ID).sections) assertTrue(s.label in loaded.finalSystem)
        assertTrue("|${SummaryConfig.format(SummaryConfig.DEFAULT_FORMAT_ID).maxWords}|${SummaryConfig.format(SummaryConfig.DEFAULT_FORMAT_ID).maxSentences}|" in loaded.finalSystem)
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
    fun theFormatIdIsPartOfThePromptVersionSoSwitchingItMakesSummariesStale() {
        assertTrue(SummaryConfig.DEFAULT_FORMAT_ID in SummaryConfig.FORMATS)
        assertTrue(SummaryConfig.fingerprint().split("|").first() == SummaryConfig.DEFAULT_FORMAT_ID)
        // The version is a hash of the prompt texts and the fingerprint: a different fingerprint gives a different version.
        val a = com.therapylocal.app.facts.Prompts.version("s", "u", "n", "nu", SummaryConfig.fingerprint())
        val b = com.therapylocal.app.facts.Prompts.version("s", "u", "n", "nu", SummaryConfig.fingerprint().replaceFirst(SummaryConfig.DEFAULT_FORMAT_ID, "dap"))
        assertTrue(a != b)
    }

    @Test
    fun onlyTheFinalPromptCarriesTheFormat() {
        for (lang in listOf("en", "cs")) {
            val loaded = SummaryPrompts.load({ name -> java.io.File("../demo/prompts", name).readText() }, lang)
            assertTrue("{{FORMAT}}" !in loaded.notesSystem)
            for (sec in SummaryConfig.format(SummaryConfig.DEFAULT_FORMAT_ID).sections) assertTrue(sec.label in loaded.finalSystem && sec.label !in loaded.notesSystem && sec.label !in loaded.notesUser)
        }
    }

    @Test
    fun theContextCountIsTheOneTheAppLoads() {
        assertEquals(MemoryConfig.LLM_MAX_TOKENS, TreeSummary.requiredContext())
    }
}
