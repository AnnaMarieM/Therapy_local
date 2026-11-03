package com.therapylocal.app.memory

import com.therapylocal.app.R
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

    private fun str(id: Int, vararg args: Any) = NoticeText.Str(id, args.toList())

    private fun job(session: String, status: JobStatus, attempts: Int = 0) =
        Job(nextId++, "process", session, session.substringBefore('_'), 0, null, "$session.md", status, attempts, 3, null, nextId, emptyList())

    private fun snap(vararg jobs: Job, progress: StepProgress? = null, stopping: Boolean = false) = QueueSnapshot.of(jobs.toList(), progress, stopping)

    @Test
    fun beforeTheFirstJobRunsItIsPreparing() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Pending)), setOf("a_1"))
        assertEquals(NoticeText.Plural(R.plurals.processing_preparing_title, 1), n.title)
        assertEquals(NoticeText.Str(R.string.processing_preparing_text), n.text)
        assertTrue(n.ongoing)
        assertTrue(n.indeterminate)
    }

    @Test
    fun readingShowsSessionPositionAndSectionProgress() {
        val s = snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Running), job("c_1", JobStatus.Pending), progress = StepProgress(JobPhase.Reading, 3, 8))
        val n = ProcessingNotice.running(s, setOf("a_1", "b_1", "c_1"))
        assertEquals(str(R.string.processing_title_analysing_of, 2, 3), n.title)
        assertEquals(str(R.string.processing_detail_reading_section, 3, 8), n.text)
        assertEquals(8, n.progressMax)
        assertEquals(3, n.progress)
        assertFalse(n.indeterminate)
    }

    @Test
    fun earlierRunsAreNotCounted() {
        val s = snap(job("old_1", JobStatus.Done), job("old_2", JobStatus.Done), job("new_1", JobStatus.Running), progress = StepProgress(JobPhase.Checking, 6, 20))
        val n = ProcessingNotice.running(s, setOf("new_1"))
        assertEquals(str(R.string.processing_title_analysing), n.title)
        assertEquals(str(R.string.processing_detail_checking_item, 6, 20), n.text)
    }

    @Test
    fun transcribingAndRetrying() {
        val t = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Transcribing, 0, 4)), setOf("a_1"))
        assertEquals(str(R.string.processing_title_transcribing), t.title)
        assertEquals(str(R.string.processing_detail_transcribing_part, 1, 4), t.text)
        val r = ProcessingNotice.running(snap(job("a_1", JobStatus.Running, attempts = 1)), setOf("a_1"))
        assertEquals(str(R.string.processing_retry_text, 2, 3), r.text)
        assertTrue(r.indeterminate)
        // once the retry is under way its progress is shown again
        val later = ProcessingNotice.running(snap(job("a_1", JobStatus.Running, attempts = 1), progress = StepProgress(JobPhase.Reading, 1, 2)), setOf("a_1"))
        assertEquals(str(R.string.processing_detail_reading_section, 1, 2), later.text)
    }

    @Test
    fun checkingWithNoCandidatesDoesNotCrash() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Checking, 0, 0)), setOf("a_1"))
        assertEquals(str(R.string.processing_detail_checking), n.text)
        assertTrue(n.indeterminate)
    }

    @Test
    fun stoppingReplacesProgress() {
        val n = ProcessingNotice.running(snap(job("a_1", JobStatus.Running), progress = StepProgress(JobPhase.Reading, 1, 2), stopping = true), setOf("a_1"))
        assertEquals(str(R.string.processing_stopping_title), n.title)
    }

    @Test
    fun finishedSuccess() {
        val n = ProcessingNotice.finished(snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Done)), setOf("a_1", "b_1"))!!
        assertTrue(n.success)
        assertEquals(str(R.string.processing_done_title), n.title)
        assertEquals(NoticeText.Plural(R.plurals.processing_done_text, 2), n.text)
        assertFalse(n.ongoing)
    }

    @Test
    fun finishedWithFailureAndStop() {
        val failed = ProcessingNotice.finished(snap(job("a_1", JobStatus.Done), job("b_1", JobStatus.Failed)), setOf("a_1", "b_1"))!!
        assertFalse(failed.success)
        assertEquals(NoticeText.Plural(R.plurals.processing_failed_title, 1), failed.title)
        assertEquals(str(R.string.processing_failed_text, 1, 2), failed.text)
        val stopped = ProcessingNotice.finished(snap(job("a_1", JobStatus.Pending), stopping = true), setOf("a_1"))!!
        assertEquals(str(R.string.processing_stopped_title), stopped.title)
        assertEquals(str(R.string.processing_stopped_text, 0, 1), stopped.text)
    }

    @Test
    fun nothingToReportWhenWaitingOrEmpty() {
        // nothing ran: waiting for setup or for a recording to end
        assertNull(ProcessingNotice.finished(snap(job("a_1", JobStatus.Pending)), setOf("a_1")))
        assertNull(ProcessingNotice.finished(snap(), emptySet()))
    }
}
