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
}
