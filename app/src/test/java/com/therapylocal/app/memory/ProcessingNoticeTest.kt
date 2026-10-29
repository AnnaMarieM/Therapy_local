package com.therapylocal.app.memory

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.StepProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingNoticeTest {
    private var nextId = 1L

    private fun job(session: String, status: JobStatus, attempts: Int = 0) =
        Job(nextId++, "process", session, session.substringBefore('_'), 0, null, "$session.md", status, attempts, 3, null, nextId, emptyList())

    private fun snap(vararg jobs: Job, progress: StepProgress? = null, stopping: Boolean = false) = QueueSnapshot.of(jobs.toList(), progress, stopping)

    @Test
    fun beforeTheFirstJobRunsItIsPreparing() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Pending)), setOf("a_1"))
        assertEquals("Preparing to process 1 session", n.title)
        assertEquals("Loading the on-device models…", n.text)
        assertTrue(n.ongoing)
        assertTrue(n.indeterminate)
    }

    @Test
    fun readingShowsSessionPositionAndSectionProgress() {
        val s = snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Running), job("c_1", JobStatus.Pending), progress = StepProgress(JobPhase.Reading, 3, 8))
        val n = ProcessingNotice.running(s, setOf("a_1", "b_1", "c_1"))
        assertEquals("Analysing session 2 of 3", n.title)
        assertEquals("Finding key points in the transcript · section 3 of 8", n.text)
        assertEquals(8, n.progressMax)
        assertEquals(3, n.progress)
        assertFalse(n.indeterminate)
    }

    @Test
    fun earlierRunsAreNotCounted() {
        val s = snap(job("old_1", JobStatus.Done), job("old_2", JobStatus.Done), job("new_1", JobStatus.Running), progress = StepProgress(JobPhase.Checking, 6, 20))
        val n = ProcessingNotice.running(s, setOf("new_1"))
        assertEquals("Analysing session", n.title)
        assertEquals("Comparing new findings with the profile · item 6 of 20", n.text)
    }

    @Test
    fun transcribingAndRetrying() {
        val t = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Transcribing, 0, 4)), setOf("a_1"))
        assertEquals("Transcribing session", t.title)
        assertEquals("Converting audio to text · part 1 of 4", t.text)
        val r = ProcessingNotice.running(snap(job("a_1", JobStatus.Running, attempts = 1)), setOf("a_1"))
        assertEquals("Something went wrong, retrying (attempt 2 of 3)", r.text)
        assertTrue(r.indeterminate)
        // once the retry is under way its progress is shown again
        val later = ProcessingNotice.running(snap(job("a_1", JobStatus.Running, attempts = 1), progress = StepProgress(JobPhase.Reading, 1, 2)), setOf("a_1"))
        assertEquals("Finding key points in the transcript · section 1 of 2", later.text)
    }

    @Test
    fun checkingWithNoCandidatesDoesNotCrash() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Checking, 0, 0)), setOf("a_1"))
        assertEquals("Comparing new findings with the profile", n.text)
        assertTrue(n.indeterminate)
    }

    @Test
    fun stoppingReplacesProgress() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Reading, 1, 2), stopping = true), setOf("a_1"))
        assertEquals("Stopping…", n.title)
    }

    @Test
    fun finishedSuccess() {
        val n = ProcessingNotice.finished(snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Done)), setOf("a_1", "b_1"))!!
        assertEquals(ProcessingNotice.TITLE_DONE, n.title)
        assertEquals("2 sessions done", n.text)
        assertFalse(n.ongoing)
    }

    @Test
    fun finishedWithFailureAndStop() {
        val failed = ProcessingNotice.finished(snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Failed)), setOf("a_1", "b_1"))!!
        assertEquals("1 session could not be processed", failed.title)
        assertEquals("1 of 2 done · open the app to retry", failed.text)
        val stopped = ProcessingNotice.finished(snap(job("a_1", JobStatus.Pending), stopping = true), setOf("a_1"))!!
        assertEquals("Processing stopped", stopped.title)
        assertEquals("0 of 1 done · open the app to resume", stopped.text)
    }

    @Test
    fun nothingToReportWhenWaitingOrEmpty() {
        // nothing ran: waiting for setup or for a recording to end
        assertNull(ProcessingNotice.finished(snap(job("a_1", JobStatus.Pending)), setOf("a_1")))
        assertNull(ProcessingNotice.finished(snap(), emptySet()))
    }
}
