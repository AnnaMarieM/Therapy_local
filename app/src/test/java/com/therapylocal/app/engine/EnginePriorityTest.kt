package com.therapylocal.app.engine

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AD-18: which priority a host gives the binder thread, per role and op. */
class EnginePriorityTest {
    private val tag = CallTag("t", null, "x")
    private val open = EngineOp.Open(tag, EngineKind.ASR, "m", "g", "CPU")
    private val embedText = EngineOp.EmbedText(tag, 1, "t", false)
    private val close = EngineOp.Close(tag, 1)

    private val all get() = listOf(open, embedText, close, EngineOp.Status(tag))

    @Test fun batchRunsEveryOpAtDefaultPriority() {
        for (op in all) assertEquals(Process.THREAD_PRIORITY_DEFAULT, EnginePriority.of(EngineRole.BATCH, op))
        assertEquals(0, Process.THREAD_PRIORITY_DEFAULT)
    }

    @Test fun liveLowersOnlyOpenAndEmbedderOps() {
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, EnginePriority.of(EngineRole.LIVE, open))
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, EnginePriority.of(EngineRole.LIVE, embedText))
        assertNull(EnginePriority.of(EngineRole.LIVE, close))
        assertNull(EnginePriority.of(EngineRole.LIVE, EngineOp.Status(tag)))
    }

    @Test fun localNeverChangesPriority() {
        for (op in all) assertNull(EnginePriority.of(EngineRole.LOCAL, op))
    }
}
