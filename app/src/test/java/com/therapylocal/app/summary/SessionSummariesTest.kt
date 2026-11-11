package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.jobs.JobFailure
import com.therapylocal.app.jobs.JobStopped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The state of a summary and its making: provenance, stale on a speaker-map swap or changed inputs, rebuild, retries. */
class SessionSummariesTest {
    private val repo = FakeSummaryRepo(shortSession()).also { it.addMap(MAP_S1_THERAPIST) }
    private val svc = SessionSummaries(repo, clock = { 1234L })
    private val prompts = testPrompts()
    private val method = TreeSummary()
    private val v = prompts.version()

    private fun build(ask: SummaryAsk = RecordingAsk()) = svc.build("s", prompts, "gemma-test", method, ask)
    private fun state(generating: Boolean = false, version: String = v) = svc.view("s", version, "gemma-test", generating).state

    @Test
    fun withoutASummaryTheStateIsNone() {
        assertEquals(SummaryState.None, state())
        assertNull(svc.view("s", v, "gemma-test", false).text)
    }

    @Test
    fun buildingStoresTheSummaryWithItsProvenanceAndItIsReady() {
        val r = build()!!
        assertEquals(1, r.calls)
        val row = repo.stored!!
        assertEquals(repo.currentTranscript("s").sha256, row.provenance.transcriptSha)
        assertEquals(1, row.provenance.speakerMapVersion)
        assertEquals(v, row.provenance.promptVersion)
        assertEquals("gemma-test", row.provenance.model)
        assertEquals(method.id, row.method)
        assertFalse(row.stale)
        assertEquals(1234L, row.createdAt)
        assertEquals(SummaryState.Ready, state())
        assertEquals(1234L, svc.view("s", v, "gemma-test", false).generatedAt)
    }

    @Test
    fun theModelSeesTheRolesOfTheCurrentMap() {
        val ask = RecordingAsk()
        build(ask)
        val user = ask.calls.single().user
        assertTrue("Therapist: How was your week?" in user)
        assertTrue("Patient: Hard, I could not sleep at all." in user)
    }

    @Test
    fun aSwapMakesTheSummaryStaleAndRebuildingUsesTheNewRoles() {
        build()
        assertEquals(SummaryState.Ready, state())

        assertEquals(2, repo.swap())
        // The swap is a new speaker-map version; refreshing the flag (what the app does right after it) marks the summary stale.
        assertFalse(repo.stored!!.stale)
        svc.refreshStale("s", v, "gemma-test")
        assertTrue(repo.stored!!.stale)
        assertEquals(SummaryState.Stale, state())

        val ask = RecordingAsk()
        build(ask)
        val user = ask.calls.single().user
        assertTrue("Patient: How was your week?" in user)
        assertTrue("Therapist: Hard, I could not sleep at all." in user)
        assertEquals(2, repo.stored!!.provenance.speakerMapVersion)
        assertEquals(SummaryState.Ready, state())
    }

    @Test
    fun refreshWithoutASummaryDoesNothing() {
        svc.refreshStale("s", v, "gemma-test")
        assertNull(repo.stored)
        assertEquals(SummaryState.None, state())
    }

    @Test
    fun anotherModelMakesItStaleAndTheSameOneClearsTheFlag() {
        build()
        assertEquals(SummaryState.Stale, svc.view("s", v, "other-model", false).state)
        assertEquals(SummaryState.Ready, svc.view("s", v, "gemma-test", false).state)
    }

    @Test
    fun aChangedTranscriptOrChangedPromptsMakeItStale() {
        build()
        assertEquals(SummaryState.Stale, state(version = "other"))
        repo.segments = repo.segments + SegmentRow(4, 12_000, 14_000, "Anything else?", "S1")
        assertEquals(SummaryState.Stale, state())
    }

    @Test
    fun generatingWinsAndTheOldTextIsStillGiven() {
        build()
        repo.swap()
        val view = svc.view("s", v, "gemma-test", generating = true)
        assertEquals(SummaryState.Generating, view.state)
        assertNotNull(view.text)
    }

    @Test
    fun withoutAMapEverySpeakerKeepsANeutralLabelAndVersionIsZero() {
        repo.maps.clear()
        val ask = RecordingAsk()
        build(ask)
        assertEquals(0, repo.stored!!.provenance.speakerMapVersion)
        assertTrue("Speaker 1: How was your week?" in ask.calls.single().user)
        assertTrue("Speaker 2: Hard" in ask.calls.single().user)
    }

    @Test
    fun aTranscriptWithoutSpeakersStaysPlain() {
        repo.segments = listOf(SegmentRow(1, 0, 1, "just text here", null))
        repo.maps.clear()
        val ask = RecordingAsk()
        build(ask)
        assertTrue("just text here" in ask.calls.single().user && "Speaker" !in ask.calls.single().user)
    }

    @Test
    fun aSessionWithoutWordsStoresNothing() {
        repo.segments = emptyList()
        assertNull(build())
        assertNull(repo.stored)
    }

    @Test
    fun aFailedOrBlankCallIsTriedAgainThenTheJobFails() {
        var n = 0
        val flaky = SummaryAsk { _, _, _ -> if (++n == 1) throw IllegalStateException("x") else "Subjective: slept badly, said so plainly this week." }
        assertNotNull(build(flaky))
        assertEquals(2, n)

        n = 0
        val blank = SummaryAsk { _, _, _ -> n++; "  " }
        try { build(blank); fail() } catch (e: JobFailure) { assertEquals("LLM", e.code) }
        assertEquals(SummaryConfig.ATTEMPTS, n)
    }

    @Test
    fun aReplyTooShortToBeASummaryFailsTheJobAndKeepsTheOldOne() {
        build()
        val old = repo.stored
        try { build(RecordingAsk(final = "ok")); fail() } catch (e: JobFailure) { assertEquals("LLM", e.code) }
        assertEquals(old, repo.stored)
    }

    @Test
    fun aDeadEngineAndAStopRequestAreNotRetried() {
        var n = 0
        try { build { _, _, _ -> n++; throw JobStopped() }; fail() } catch (_: JobStopped) {}
        assertEquals(1, n)
        try { build { _, _, _ -> n++; throw EngineUnavailable(com.therapylocal.app.engine.EngineRole.BATCH, "x", 1L) }; fail() } catch (_: EngineUnavailable) {}
        assertEquals(2, n)
        assertNull(repo.stored)
    }

    @Test
    fun codeFencesAroundTheReplyAreRemoved() {
        build(RecordingAsk(final = "```\nSubjective: slept badly this week, said so plainly.\n```"))
        assertEquals("Subjective: slept badly this week, said so plainly.", repo.stored!!.text)
    }

    @Test
    fun aSwapDuringTheRunLeavesTheStoredSummaryStale() {
        // The inputs are read at the start: the summary says which map it used, so the swap that came meanwhile still shows.
        val ask = SummaryAsk { _, _, _ -> repo.swap(); "Subjective: slept badly this week, said so plainly." }
        build(ask)
        assertEquals(1, repo.stored!!.provenance.speakerMapVersion)
        assertEquals(SummaryState.Stale, state())
    }

    @Test
    fun sectionsAreSplitAtTheConfiguredHeadings() {
        val text = "Subjective: Slept badly.\nStill tired.\n**Objective:** Calm.\nAssessment:\nInsomnia theme.\nPlanning to rest is not a heading.\nPlan: Sleep diary."
        val s = SummarySections.parse(text)
        assertEquals(listOf("Subjective", "Objective", "Assessment", "Plan"), s.map { it.label })
        assertEquals("Slept badly.\nStill tired.", s[0].text)
        assertEquals("Calm.", s[1].text)
        assertEquals("Insomnia theme.\nPlanning to rest is not a heading.", s[2].text)
        assertEquals("Sleep diary.", s[3].text)
        assertEquals(listOf(""), SummarySections.parse("no headings at all").map { it.label })
    }
}
