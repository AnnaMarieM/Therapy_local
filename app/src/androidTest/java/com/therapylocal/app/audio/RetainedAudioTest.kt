package com.therapylocal.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.data.PendingAudio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Finished recordings keep their (encrypted) audio for a week. Uses its own session names and file ages, so it never touches or expires the
 * phone's real retained audio. The files hold arbitrary bytes: only moving, ageing and deleting are under test, not the encryption.
 */
@RunWith(AndroidJUnit4::class)
class RetainedAudioTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 24 * 60 * 60 * 1000L
    private val sessions = listOf("rtest_20250101-100000", "rtest_20250102-100000")
    private val recordings = listOf(9_001L, 9_002L, 9_003L)

    @After
    fun clean() {
        sessions.forEach { PendingAudio.deleteRetained(context, it) }
        recordings.forEach { PendingAudio.delete(context, it) }
    }

    private fun pending(rid: Long, chunks: Int = 2) {
        File(PendingAudio.dir(context, rid), "x").parentFile!!.mkdirs()
        repeat(chunks) { File(PendingAudio.dir(context, rid), "$it.pcm").writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun retainingMovesTheAudioOutOfThePendingFolderAndKeepsIt() {
        pending(9_001L)
        PendingAudio.retain(context, 9_001L, sessions[0])

        assertFalse("no longer pending", PendingAudio.has(context, 9_001L))
        assertEquals(listOf(0, 1), PendingAudio.retainedDir(context, sessions[0], 9_001L).listFiles()!!.map { it.name.removeSuffix(".pcm").toInt() }.sorted())
        assertTrue((sessions[0] to 9_001L) in PendingAudio.retained(context))
    }

    @Test
    fun retainingARecordingWithNoAudioDoesNothing() {
        PendingAudio.retain(context, 9_003L, sessions[0])
        assertFalse((sessions[0] to 9_003L) in PendingAudio.retained(context))
    }

    @Test
    fun onlyRecordingsOlderThanAWeekExpire() {
        pending(9_001L); PendingAudio.retain(context, 9_001L, sessions[0])
        pending(9_002L); PendingAudio.retain(context, 9_002L, sessions[1])
        // Age the first one to 8 days, the second to 6 days.
        PendingAudio.retainedDir(context, sessions[0], 9_001L).walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 8 * day) }
        PendingAudio.retainedDir(context, sessions[1], 9_002L).walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 6 * day) }

        PendingAudio.sweepRetained(context)

        val left = PendingAudio.retained(context)
        assertFalse("8 days old: gone", (sessions[0] to 9_001L) in left)
        assertTrue("6 days old: kept", (sessions[1] to 9_002L) in left)
    }

    @Test
    fun deletingASessionRemovesItsRetainedAudioAtOnceAndLeavesTheOthers() {
        pending(9_001L); PendingAudio.retain(context, 9_001L, sessions[0])
        pending(9_002L); PendingAudio.retain(context, 9_002L, sessions[1])

        PendingAudio.deleteRetained(context, sessions[0])

        val left = PendingAudio.retained(context)
        assertFalse((sessions[0] to 9_001L) in left)
        assertTrue((sessions[1] to 9_002L) in left)
    }
}
