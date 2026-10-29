package com.therapylocal.app.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingNoticeTest {
    private fun job(id: String, status: JobStatus, phase: JobPhase = JobPhase.None, step: Int = 0, steps: Int = 0, added: Int = 0) =
        SessionJob(id, id.substringBefore('_'), "$id.md", "2026-10-29", status = status, phase = phase, step = step, steps = steps, added = added)

    private fun state(vararg jobs: SessionJob, running: Boolean = true, message: String = "Running") =
        QueueState(running = running, message = message, jobs = jobs.toList())

    @Test
    fun loadingModelsIsIndeterminate() {
        val n = ProcessingNotice.running(state(job("a_1", JobStatus.Queued), message = "Loading models"), setOf("a_1"), stopping = false)
        assertEquals("Preparing to process 1 session", n.title)
        assertEquals("Loading the on-device models…", n.text)
        assertTrue(n.ongoing)
        assertTrue(n.indeterminate)
    }

    @Test
    fun readingShowsSessionPositionAndSectionProgress() {
        val s = state(job("a_1", JobStatus.Done), job("b_1", JobStatus.Running, JobPhase.Reading, 3, 8), job("c_1", JobStatus.Queued))
        val n = ProcessingNotice.running(s, setOf("a_1", "b_1", "c_1"), stopping = false)
        assertEquals("Analysing session 2 of 3", n.title)
        assertEquals("Finding key points in the transcript · section 3 of 8", n.text)
        assertEquals(8, n.progressMax)
        assertEquals(3, n.progress)
        assertFalse(n.indeterminate)
    }

    @Test
    fun earlierRunsAreNotCounted() {
        val s = state(job("old_1", JobStatus.Done), job("old_2", JobStatus.Done), job("new_1", JobStatus.Running, JobPhase.Checking, 6, 20))
        val n = ProcessingNotice.running(s, setOf("new_1"), stopping = false)
        assertEquals("Analysing session", n.title)
        assertEquals("Comparing new findings with the profile · item 6 of 20", n.text)
    }

    @Test
    fun transcribingAndRetrying() {
        val t = ProcessingNotice.running(state(job("a_1", JobStatus.Running, JobPhase.Transcribing, 0, 4)), setOf("a_1"), false)
        assertEquals("Transcribing session", t.title)
        assertEquals("Converting audio to text · part 1 of 4", t.text)
        val r = ProcessingNotice.running(state(job("a_1", JobStatus.Running, JobPhase.Retrying, 2, 3)), setOf("a_1"), false)
        assertEquals("Something went wrong, retrying (attempt 2 of 3)", r.text)
        assertTrue(r.indeterminate)
    }

    @Test
    fun checkingWithNoCandidatesDoesNotCrash() {
        val n = ProcessingNotice.running(state(job("a_1", JobStatus.Running, JobPhase.Checking, 0, 0)), setOf("a_1"), false)
        assertEquals("Comparing new findings with the profile", n.text)
        assertTrue(n.indeterminate)
    }

    @Test
    fun stoppingReplacesProgress() {
        val n = ProcessingNotice.running(state(job("a_1", JobStatus.Running, JobPhase.Reading, 1, 2)), setOf("a_1"), stopping = true)
        assertEquals("Stopping…", n.title)
    }

    @Test
    fun finishedSuccess() {
        val s = state(job("a_1", JobStatus.Done, added = 4), job("b_1", JobStatus.Done, added = 1), running = false, message = "Finished")
        val n = ProcessingNotice.finished(s, setOf("a_1", "b_1"))!!
        assertEquals(ProcessingNotice.TITLE_DONE, n.title)
        assertEquals("2 sessions done · 5 new facts added", n.text)
        assertFalse(n.ongoing)
    }

    @Test
    fun finishedWithFailureAndStop() {
        val failed = ProcessingNotice.finished(state(job("a_1", JobStatus.Done), job("b_1", JobStatus.Failed), running = false, message = "Finished"), setOf("a_1", "b_1"))!!
        assertEquals("1 session could not be processed", failed.title)
        val stopped = ProcessingNotice.finished(state(job("a_1", JobStatus.Cancelled), running = false, message = "Stopped"), setOf("a_1"))!!
        assertEquals("Processing stopped", stopped.title)
    }

    @Test
    fun nothingToReportWhenWaitingForSetupOrEmpty() {
        assertNull(ProcessingNotice.finished(state(job("a_1", JobStatus.Queued), running = false, message = "Waiting for setup"), setOf("a_1")))
        assertNull(ProcessingNotice.finished(state(running = false, message = "Finished"), emptySet()))
    }

    @Test
    fun phaseSurvivesJsonAndOldJsonDefaults() {
        val j = job("a_1", JobStatus.Done, JobPhase.Checking, 2, 5)
        val back = SessionJob.fromJson(j.toJson())
        assertEquals(JobPhase.Checking, back.phase)
        assertEquals(2, back.step)
        assertEquals(5, back.steps)
        val old = SessionJob.fromJson(JSONObject().put("id", "x_1").put("client", "x").put("file", "x_1.md"))
        assertEquals(JobPhase.None, old.phase)
        assertEquals(0, old.steps)
    }
}
