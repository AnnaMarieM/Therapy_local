package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelDispatchersTest {
    @Test fun priorityIsSetOnTheWorkerNotTheCreatingThread() {
        val calls = mutableListOf<Pair<String, Int>>()
        val factory = ModelDispatchers.threadFactory { p -> synchronized(calls) { calls += Thread.currentThread().name to p } }
        val submitter = Thread.currentThread()
        val before = submitter.priority
        val done = CountDownLatch(1)
        val ran = AtomicReference<String>()
        val t = factory.newThread { ran.set(Thread.currentThread().name); done.countDown() }
        assertTrue("nothing set on the creating thread", synchronized(calls) { calls.isEmpty() })
        t.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.join(5000)
        assertEquals(before, submitter.priority)
        assertTrue(ran.get().startsWith("model-compute"))
        synchronized(calls) {
            assertEquals(1, calls.size)
            assertEquals(ran.get(), calls[0].first)
        }
    }

    @Test fun threadsAreNumbered() {
        val f = ModelDispatchers.threadFactory { }
        assertEquals("model-compute-1", f.newThread { }.name)
        assertEquals("model-compute-2", f.newThread { }.name)
    }
}
