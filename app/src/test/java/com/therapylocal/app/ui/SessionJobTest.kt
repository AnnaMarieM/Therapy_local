package com.therapylocal.app.ui

import com.therapylocal.app.memory.JobStatus
import com.therapylocal.app.memory.SessionJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionJobTest {
    @Test
    fun newJobIsQueuedWithNoStage() {
        val job = SessionJob(id = "c_1", client = "c", file = "c_1.md", date = "2026-10-25")
        assertEquals(JobStatus.Queued, job.status)
        assertEquals("", job.stage)
        assertEquals(0L, job.recordingId)
        assertNull(job.error)
    }
}
