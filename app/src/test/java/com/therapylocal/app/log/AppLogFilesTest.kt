package com.therapylocal.app.log

import com.therapylocal.app.engine.EngineProcess
import com.therapylocal.app.engine.EngineRole
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogFilesTest {
    private fun tmp(): File = Files.createTempDirectory("applog").toFile().also { it.deleteOnExit() }

    @Test fun lineCarriesProcAndPidAfterSid() {
        val l = AppLog.line("2026-01-01T00:00:00.000", android.util.Log.INFO, "T", "batch", 42, LogCtx.NONE, "e", arrayOf("a" to 1), null)
        assertTrue(l, l.contains(" sid=${AppLog.sid} proc=batch pid=42 evt=e a=1"))
    }

    @Test fun processNames() {
        assertNull(EngineProcess.roleOf("com.therapylocal.app"))
        assertNull(EngineProcess.roleOf(null))
        assertEquals(EngineRole.LIVE, EngineProcess.roleOf("com.therapylocal.app:live"))
        assertEquals(EngineRole.BATCH, EngineProcess.roleOf("com.therapylocal.app:batch"))
        assertEquals("main", EngineProcess.labelOf("com.therapylocal.app"))
    }

    @Test fun tailAllMergesByTsStably() {
        val d = tmp()
        File(d, "tl.log").writeText("ts=2026-01-01T00:00:01.000 a\nts=2026-01-01T00:00:03.000 b\nts=2026-01-01T00:00:05.000 same-main\n")
        File(d, "engine-live.log").writeText("ts=2026-01-01T00:00:02.000 c\n")
        File(d, "engine-batch.log").writeText("ts=2026-01-01T00:00:04.000 d\nts=2026-01-01T00:00:05.000 same-batch\ntrace line after\n")
        val out = LogMerge.tailAll(d, listOf("tl", "engine-live", "engine-batch"), 100).lines().map { if (it.startsWith("ts=")) it.substringAfter(' ') else it }
        assertEquals(listOf("a", "c", "b", "d", "same-main", "same-batch", "trace line after"), out.map { it.trim() })
        assertEquals(2, LogMerge.tailAll(d, listOf("tl", "engine-live", "engine-batch"), 2).lines().size)
    }

    @Test fun writerReopensAfterDelete() {
        val d = tmp()
        var t = 0L
        val log = RotatingLog(d, "engine-batch", 1_000_000, 5, recheckMs = 1000, now = { t })
        log.open()
        log.write("one")
        log.file.delete()
        t += 1500
        log.write("two")
        assertEquals("two\n", log.file.readText())
        log.close()
    }

    @Test fun clearDeletesOnlyOwnBase() {
        val d = tmp()
        val mine = RotatingLog(d, "tl", 1_000_000, 5).also { it.open() }
        mine.write("x")
        File(d, "tl.1.log").writeText("old")
        File(d, "engine-batch.log").writeText("host")
        File(d, "engine-batch.1.log").writeText("host old")
        mine.clear()
        assertEquals("", mine.file.readText())
        assertFalse(File(d, "tl.1.log").exists())
        assertTrue(File(d, "engine-batch.log").exists())
        assertTrue(File(d, "engine-batch.1.log").exists())
        mine.close()
    }

    @Test fun rotationKeepsFiles() {
        val d = tmp()
        val log = RotatingLog(d, "tl", 10, 3).also { it.open() }
        repeat(4) { log.write("0123456789ab$it") }
        assertTrue(File(d, "tl.1.log").exists())
        assertTrue(File(d, "tl.2.log").exists())
        assertFalse(File(d, "tl.3.log").exists())
        log.close()
    }
}
