package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.memory.RetryProcessingWorker
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SharedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Fixes from the review of the engine-process work: close ordering and settling, idle re-arm, kept hang verdict, no binding for logging. */
class EngineReviewFixesTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-caller").apply { isDaemon = true } }
    private fun <T> async(f: () -> T): Future<T> = pool.submit<T> { f() }
    private fun closer() = Executors.newSingleThreadExecutor { r -> Thread(r, "test-close").apply { isDaemon = true } }
    private val fast = EngineTuning(askMinTimeoutMs = 1000, openTimeoutMs = 1000, embedTimeoutMs = 1000, hangNoProgressMs = 400, closeTimeoutMs = 500, idleMs = 10_000_000)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out: $what" }
            Thread.sleep(2)
        }
    }

    // --- 1: a close is ordered before the next Open

    @Test fun anOpenWaitsForTheCloseSubmittedBeforeIt() {
        val r = Rig(tuning = fast, autoAdvance = false, closeExecutor = closer())
        val first = r.open("GPU", "m1")
        val gate = CountDownLatch(1)
        r.binding.script = { _, op -> if (op is EngineOp.Close) gate.await(10, TimeUnit.SECONDS).let { null } else null }
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), first.handle))
        val second = async { r.open("CPU", "m2") }
        waitFor("close in the binding") { r.binding.calls.any { it.second is EngineOp.Close } }
        Thread.sleep(150)
        assertFalse("the Open must wait for the Close", second.isDone)
        assertEquals(1, r.binding.calls.count { it.second is EngineOp.Open })
        gate.countDown()
        second.get(10, TimeUnit.SECONDS)
        val kinds = r.binding.calls.map { it.second.tag.op }
        assertTrue(kinds.toString(), kinds.indexOf("close") < kinds.lastIndexOf("open"))
    }

    @Test fun theWaitForPendingClosesIsBoundedByTheCloseTimeout() {
        val r = Rig(tuning = fast, autoAdvance = true, closeExecutor = java.util.concurrent.Executor { /* never runs */ })
        val h = r.open("GPU")
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle))
        val t0 = r.time.now
        r.conn.awaitPendingCloses()
        assertTrue(r.time.now - t0 >= fast.closeTimeoutMs)
        assertEquals(1, r.log.all(EngineEvents.CALL_FAILED).count { it.f["why"] == "close_wait_timeout" })
    }

    // --- 4: a death before the close is acknowledged still strikes the handle

    @Test fun aDeathWhileACloseIsPendingSettlesTheHandleAsADeathAndTheCloseIsNotAcked() {
        val r = Rig(tuning = fast, autoAdvance = false, closeExecutor = closer())
        val h = r.open("GPU")
        r.binding.script = { p, op -> if (op is EngineOp.Close) r.binding.block(p) else null }
        val acks = CopyOnWriteArrayList<Boolean>()
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle)) { acks += it }
        assertTrue(r.binding.inCall.await(10, TimeUnit.SECONDS))
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        waitFor("close finished") { acks.isNotEmpty() }
        assertEquals(listOf(false), acks.toList())
        assertEquals(1, r.settler.died.size)
        assertTrue(r.settler.unloaded.isEmpty())
        assertEquals(ExitKind.NATIVE_CRASH, r.settler.died.single().third.kind)
    }

    @Test fun anAcknowledgedCloseIsAckedOnceAndALaterDeathSettlesNothing() {
        val r = Rig()
        val h = r.open("GPU")
        val acks = CopyOnWriteArrayList<Boolean>()
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle)) { acks += it }
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle)) { acks += it } // a second close of the same handle
        assertEquals(listOf(true, false), acks.toList())
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        assertTrue(r.settler.died.isEmpty())
        assertTrue(r.settler.unloaded.isEmpty())
    }

    @Test fun aSynchronousCloseMetWhileTheGenerationIsStillDyingKeepsTheStrike() {
        val time = ManualTime()
        val steps = StepExecutor(time)
        val r = Rig(time = time, scheduler = steps)
        val h = r.open("GPU")
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 11) // binderDied queued, not run: the generation is DYING
        assertEquals(EngineReply.Done, r.conn.call(EngineOp.Close(r.tag("close"), h.handle)))
        steps.drain()
        assertEquals(1, r.settler.died.size)
    }

    // --- 8: the idle timer survives a poll that ran across its check

    @Test fun aStatusPollThatRunsAcrossTheIdleCheckDoesNotLeaveTheHostWithoutAnIdleTimer() {
        val r = Rig()
        r.open()
        r.binding.script = { _, op -> if (op is EngineOp.Status) r.time.advance(r.tuning.idleMs); null }
        assertNotNull(r.conn.statusIfBound())
        assertEquals("the check ran during the poll and found a flight", 0, r.log.count(EngineEvents.IDLE_STOP))
        r.binding.script = { _, _ -> null }
        r.time.advance(r.tuning.idleMs)
        assertEquals(1, r.log.count(EngineEvents.IDLE_STOP))
    }

    // --- 10: asking for a stop does not undo a hang verdict

    @Test fun aReleaseAfterAHangWasDecidedKeepsTheHangStrike() {
        val r = Rig()
        r.open("GPU")
        assertNotNull(r.conn.expectedExit(ExitKind.ANR, "hang"))
        r.conn.release()
        assertEquals(1, r.settler.died.size)
        assertEquals(ExitKind.ANR, r.settler.died.single().third.kind)
        assertTrue(r.settler.unloaded.isEmpty())
    }

    // --- 7: logging never binds a host

    private class ConnHost(val conn: EngineConnection, override val role: EngineRole) : EngineHost {
        override fun call(op: EngineOp, phases: PhaseSink): EngineReply = conn.call(op, phases)
        override fun closeAsync(op: EngineOp.Close) = conn.closeAsync(op)
        override fun closeAsync(op: EngineOp.Close, onClosed: (acked: Boolean) -> Unit) = conn.closeAsync(op, onClosed)
        override fun awaitPendingCloses() = conn.awaitPendingCloses()
        override fun statusIfBound() = conn.statusIfBound()
    }

    @Test fun statusIfBoundOnTheProxyNeverBindsAHost() {
        val r = Rig()
        val proxy = EngineProxy({ ConnHost(r.conn, EngineRole.BATCH) }, "T", "t", { _, _, _ -> })
        assertNull(proxy.statusIfBound())
        assertTrue(r.binding.binds.isEmpty())
        r.open()
        assertNotNull(proxy.statusIfBound())
        assertEquals(listOf(1), r.binding.binds)
    }

    // --- 1 and 4 at the port: MemoryLlm tells the guard `unloaded` only after the ack, and waits for earlier closes before its load

    private val context: Context = ContextWrapper(null)

    @Test fun memoryLlmWaitsForEarlierClosesBeforeTheGateAndUnloadsInTheGuardOnlyAfterTheAck() {
        val host = FakeHost()
        val store = SharedStore()
        val guard = BackendGuard(store, "build")
        val gates = CopyOnWriteArrayList<String>()
        val llm = MemoryLlm(
            context, 4096, ModelRegistry.GEMMA_E2B, host, ladder = { BackendLadder(guard) }, guard = { guard },
            gate = { name -> host.order += "gate"; gates += name; null },
            proxy = EngineProxy({ host }, "T", "t", { m, b, p -> guard.phase(m, b, p) }),
        )
        guard.failed(ModelRegistry.GEMMA_E2B.id, "GPU", "earlier") // a strike that only a clean unload clears
        llm.load(listOf("GPU"))
        assertEquals(listOf("await", "gate"), host.order.toList())
        val before = guard.record(ModelRegistry.GEMMA_E2B.id, "GPU")!!.strikes
        assertTrue(before > 0)
        host.deferClose = true
        llm.close()
        assertEquals("not unloaded while the close is only asked for", before, guard.record(ModelRegistry.GEMMA_E2B.id, "GPU")!!.strikes)
        host.ackCloses(acked = false) // the host died first: the registry settles it, the guard must not hear `unloaded`
        assertEquals(before, guard.record(ModelRegistry.GEMMA_E2B.id, "GPU")!!.strikes)
    }

    @Test fun memoryLlmSettlesTheGuardWhenTheCloseIsAcked() {
        val host = FakeHost()
        val guard = BackendGuard(SharedStore(), "build")
        val id = ModelRegistry.GEMMA_E2B.id
        val llm = MemoryLlm(
            context, 4096, ModelRegistry.GEMMA_E2B, host, ladder = { BackendLadder(guard) }, guard = { guard }, gate = { null },
            proxy = EngineProxy({ host }, "T", "t", { m, b, p -> guard.phase(m, b, p) }),
        )
        guard.failed(id, "GPU", "earlier")
        llm.load(listOf("GPU"))
        assertEquals(BackendGuard.State.PROVEN, guard.record(id, "GPU")!!.state)
        assertTrue(guard.record(id, "GPU")!!.strikes > 0)
        host.deferClose = true
        llm.close()
        assertTrue(guard.record(id, "GPU")!!.strikes > 0)
        host.ackCloses(acked = true)
        assertEquals(0, guard.record(id, "GPU")!!.strikes)
    }

    // --- 6: the deferral floor and growth

    @Test fun aDeferralWaitsAtLeastThirtySecondsAndGrowsWithEachOneInARow() {
        assertEquals(30_000L, RetryProcessingWorker.delayFor(500, 1))
        assertEquals(30_000L, RetryProcessingWorker.delayFor(0, 1))
        assertEquals(42_000L, RetryProcessingWorker.delayFor(42_000, 1))
        assertEquals(60_000L, RetryProcessingWorker.delayFor(500, 2))
        assertEquals(120_000L, RetryProcessingWorker.delayFor(500, 3))
        assertEquals(RetryProcessingWorker.MAX_DELAY_MS, RetryProcessingWorker.delayFor(500, 40))
    }

    // --- 9: closeAll takes the runner's lock, with a bound

    @Test fun withMonitorRunsUnderTheLockAndGivesUpWhenItIsHeld() {
        val lock = Any()
        val ran = CopyOnWriteArrayList<String>()
        assertTrue(withMonitor(lock, 1000) { ran += "free" })
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.execute { synchronized(lock) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        held.await(10, TimeUnit.SECONDS)
        assertFalse(withMonitor(lock, 100) { ran += "never" })
        release.countDown()
        Thread.sleep(100)
        assertEquals(listOf("free"), ran.toList())
    }
}
