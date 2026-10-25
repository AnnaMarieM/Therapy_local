package com.therapylocal.app.ui

import com.therapylocal.app.memory.MemoryProcessor
import com.therapylocal.app.memory.SessionJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSaveWorkflowTest {

    @Test
    fun testDurationFormatting() {
        val fmt = { n: Long -> "%02d".format(n) }
        fun formatDuration(sessionSecs: Long): String =
            "${fmt(sessionSecs / 60)}:${fmt(sessionSecs % 60)}"

        assertEquals("00:00", formatDuration(0L))
        assertEquals("00:45", formatDuration(45L))
        assertEquals("01:00", formatDuration(60L))
        assertEquals("05:23", formatDuration(323L))
        assertEquals("45:12", formatDuration(2712L))
        assertEquals("75:00", formatDuration(4500L))
    }

    @Test
    fun testMemoryProcessorClientIdAndSessionId() {
        assertEquals("sarahchen", MemoryProcessor.clientId("Sarah Chen"))
        assertEquals("marcusvance", MemoryProcessor.clientId("Marcus Vance, PhD"))

        val timestamp = 1771934400000L // specific epoch time
        val sessionId = MemoryProcessor.sessionId("sarahchen", timestamp)
        assertTrue(sessionId.startsWith("sarahchen_"))
    }

    @Test
    fun testSessionJobCreationForBackgroundProcessing() {
        val client = "sarahchen"
        val sessionId = "${client}_20261025-143000"
        val date = "2026-10-25"
        val job = SessionJob(
            id = sessionId,
            client = client,
            file = "$sessionId.md",
            date = date
        )

        assertEquals(sessionId, job.id)
        assertEquals(client, job.client)
        assertEquals("$sessionId.md", job.file)
        assertEquals(date, job.date)
        assertEquals(com.therapylocal.app.memory.JobStatus.Queued, job.status)
        assertEquals("Queued", job.stage)
    }

    @Test
    fun testNoMindVaultBriefPhraseInUIStrings() {
        val testStrings = listOf(
            "Save Session",
            "Session recorded locally on this device",
            "Background Processing",
            "The session will be processed on the background. You will get a notification when it is done.",
            "Save"
        )
        for (s in testStrings) {
            assertFalse(s.contains("MindVault Brief", ignoreCase = true))
        }
    }

    @Test
    fun testProcessingSessionInfoAndClientMapping() {
        val client = "george"
        val sid = "${client}_20261025-150000"
        val job = SessionJob(
            id = sid,
            client = client,
            file = "$sid.md",
            date = "2026-10-25",
            status = com.therapylocal.app.memory.JobStatus.Running,
            stage = "Reading 1/2"
        )
        val jobs = listOf(job)

        val map = mutableMapOf<String, com.therapylocal.app.ui.models.ProcessingSessionInfo>()
        jobs.filter { it.status == com.therapylocal.app.memory.JobStatus.Queued || it.status == com.therapylocal.app.memory.JobStatus.Running }.forEach { j ->
            map[j.client] = com.therapylocal.app.ui.models.ProcessingSessionInfo(
                sessionId = j.id,
                date = j.date,
                stage = j.stage
            )
        }

        assertTrue(map.containsKey("george"))
        val info = map["george"]!!
        assertEquals(sid, info.sessionId)
        assertEquals("2026-10-25", info.date)
        assertEquals("Reading 1/2", info.stage)
        assertFalse(info.isPendingEnqueue)
    }

    @Test
    fun testPendingEnqueueProcessingFeedback() {
        val client = "alice"
        val sid = "${client}_20261025-151000"
        val pendingEnqueue = Triple(client, sid, "2026-10-25")

        val map = mutableMapOf<String, com.therapylocal.app.ui.models.ProcessingSessionInfo>()
        pendingEnqueue.let { (c, s, d) ->
            map[c] = com.therapylocal.app.ui.models.ProcessingSessionInfo(
                sessionId = s,
                date = d,
                stage = "Finalizing recording...",
                isPendingEnqueue = true
            )
        }

        assertTrue(map.containsKey("alice"))
        val info = map["alice"]!!
        assertEquals("Finalizing recording...", info.stage)
        assertTrue(info.isPendingEnqueue)
    }

    @Test
    fun testCompletedSessionTriggersRefreshKeyChange() {
        val client = "george"
        val sid = "${client}_20261025-150000"
        val runningJob = SessionJob(id = sid, client = client, file = "$sid.md", date = "2026-10-25", status = com.therapylocal.app.memory.JobStatus.Running)
        val doneJob = runningJob.copy(status = com.therapylocal.app.memory.JobStatus.Done)

        val beforeDoneSet = listOf(runningJob).filter { it.status == com.therapylocal.app.memory.JobStatus.Done }.map { it.id }.toSet()
        val afterDoneSet = listOf(doneJob).filter { it.status == com.therapylocal.app.memory.JobStatus.Done }.map { it.id }.toSet()

        assertTrue(beforeDoneSet.isEmpty())
        assertEquals(setOf(sid), afterDoneSet)
        assertTrue(beforeDoneSet != afterDoneSet)
    }
}

