package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobQueue
import com.therapylocal.app.testsupport.FakeJobRepo
import com.therapylocal.app.ui.data.SessionSummaryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The API a screen uses (none/generating/ready/stale, regenerate, rebuild on swap), the queue side of it, and the prompt files. */
class SummaryRepositoryTest {
    private val store = FakeSummaryRepo(shortSession()).also { it.addMap(MAP_S1_THERAPIST) }
    private val jobs = FakeJobRepo().also { it.sessions["s"] = "c" to 1L }
    private val queue = JobQueue(jobs, clock = { 1L })
    private val prompts = testPrompts()
    private val svc = SessionSummaries(store, clock = { 5L })

    /** Wired like production except for the Android parts: the queue is the real one over a fake repo, "enqueue" does not start a runner. */
    private val api = SessionSummaryRepository(
        repo = { store },
        lastSession = { client -> if (client == "c") "s" else null },
        promptVersion = { prompts.version() },
        model = { "m" },
        generating = { queue.unfinished(JobGraph.SUMMARY, it)?.let { j -> j.status != JobStatus.Failed } == true },
        enqueue = { JobGraph.enqueueSummary(queue, it) },
    )

    private fun make() = svc.build("s", prompts, "m", TreeSummary(), RecordingAsk())

    @Test
    fun aClientWithoutATranscriptHasNoLastSummary() {
        assertNull(api.lastSessionSummary("nobody"))
    }

    @Test
    fun theLastSessionMovesThroughNoneGeneratingReadyAndStale() {
        assertEquals(SummaryState.None, api.lastSessionSummary("c")!!.state)

        api.regenerate("s")
        assertEquals(SummaryState.Generating, api.lastSessionSummary("c")!!.state)

        // The runner does the job.
        val job = queue.claimNext { true }!!
        assertEquals(JobGraph.SUMMARY, job.kind)
        make()
        queue.complete(job)
        assertEquals(SummaryState.Ready, api.lastSessionSummary("c")!!.state)

        store.swap()
        assertEquals(SummaryState.Stale, api.lastSessionSummary("c")!!.state)
    }

    @Test
    fun regenerateWhileOneIsQueuedQueuesNothingMore() {
        api.regenerate("s")
        api.regenerate("s")
        assertEquals(1, jobs.jobs().count { it.kind == JobGraph.SUMMARY })
    }

    @Test
    fun aSwapMarksTheSummaryStaleAndQueuesARebuild() {
        make()
        store.swap()
        api.onRolesSwapped("s")
        assertTrue(store.stored!!.stale)
        assertEquals(SummaryState.Generating, api.summary("s").state)
        assertEquals(1, jobs.jobs().count { it.kind == JobGraph.SUMMARY })
        // The old text stays available while it is made again.
        assertTrue(api.summary("s").text!!.isNotBlank())
    }

    @Test
    fun aSwapWithoutASummaryQueuesNothing() {
        api.onRolesSwapped("s")
        assertEquals(0, jobs.jobs().count { it.kind == JobGraph.SUMMARY })
        assertEquals(SummaryState.None, api.summary("s").state)
    }

    @Test
    fun aFailedSummaryJobIsNotGeneratingAndRegenerateStartsAgain() {
        make()
        store.swap()
        val id = queue.enqueue(JobGraph.SUMMARY, "s")
        val job = queue.claimNext { true }!!
        queue.fail(job, "LLM", permanent = true)
        assertEquals(SummaryState.Stale, api.summary("s").state)
        // The production wiring cancels the failed job first (JobQueue.enqueue reuses an unfinished one, a failed one included).
        queue.cancel(jobs.jobs().first { it.id == id })
        api.regenerate("s")
        assertEquals(SummaryState.Generating, api.summary("s").state)
    }

    @Test
    fun theSummaryJobIsNotPartOfTheFactChainAndNeedsNoRecording() {
        val job = JobGraph.enqueueSummary(queue, "s")
        val row = jobs.jobs().single { it.id == job }
        assertNull(row.recordingId)
        assertEquals(emptyList<Long>(), row.dependsOn)
        assertEquals(job, JobGraph.enqueueSummary(queue, "s"))
    }

    @Test
    fun everyPromptFileExistsInBothLanguagesWithTheSamePlaceholders() {
        val dir = File("../demo/prompts")
        val placeholder = Regex("\\{\\{([A-Z_]+)}}")
        for (name in listOf(SummaryConfig.PROMPT_FINAL_SYSTEM, SummaryConfig.PROMPT_FINAL_USER, SummaryConfig.PROMPT_NOTES_SYSTEM, SummaryConfig.PROMPT_NOTES_USER)) {
            val en = File(dir, name).readText()
            val cs = File(dir, "cs/$name").readText()
            val enHolders = placeholder.findAll(en).map { it.groupValues[1] }.toSet()
            assertEquals(name, enHolders, placeholder.findAll(cs).map { it.groupValues[1] }.toSet())
            assertTrue(name, enHolders.isNotEmpty())
        }
        // The system prompts take the format and limits only from the config placeholders, the user prompts the text.
        val system = File(dir, SummaryConfig.PROMPT_FINAL_SYSTEM).readText()
        for (p in listOf("FORMAT", "MAX_WORDS", "MAX_SENTENCES")) assertTrue(p, "{{$p}}" in system)
        for (sec in SummaryConfig.format(SummaryConfig.DEFAULT_FORMAT_ID).sections) assertTrue("section heading is not written in the prompt file: ${sec.label}", sec.label !in system)
    }
}
