package com.therapylocal.app.jobs

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDispatchersPriorityTest {
    @Test fun workersRunAtBackgroundAndTheCallerIsUnchanged() = runBlocking {
        val before = Process.getThreadPriority(Process.myTid())
        val (name, prio) = withContext(ModelDispatchers.Compute) { Thread.currentThread().name to Process.getThreadPriority(Process.myTid()) }
        assertTrue(name, name.startsWith("model-compute"))
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, prio)
        assertEquals(before, Process.getThreadPriority(Process.myTid()))
    }
}
