package com.therapylocal.app.engine

import android.os.DeadObjectException
import com.therapylocal.app.config.EngineTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One small proof per shared JVM helper of the Step 12 suites: [ManualClock], [StepExecutor], [FakeHost] and [FakeBinding]. */
class EngineHelpersTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "helper-caller").apply { isDaemon = true } }

    @Test fun manualClockWallJumpDoesNotMoveTheMonotonicClock() {
        val c = ManualClock()
        val wall0 = c.wallMs()
        c.advance(250)
        c.jumpWall(3_600_000)
        assertEquals(250, c.nowMs())
        assertEquals(wall0 + 250 + 3_600_000, c.wallMs())
    }

    @Test fun stepExecutorRunsNothingUntilTheTestSteps() {
        val steps = StepExecutor(ManualClock())
        val ran = ArrayList<String>()
        steps.execute { ran += "task1" }
        steps.schedule(500) { ran += "timer500"; steps.execute { ran += "follow-up" } }
        steps.schedule(100) { ran += "timer100" }
        assertTrue(ran.isEmpty())
        assertEquals(1, steps.readyCount)
        assertEquals(2, steps.timerCount)
        steps.advance(499)
        assertEquals(listOf("task1", "timer100"), ran)
        assertEquals(499, steps.clock.nowMs())
        steps.advance(1)
        assertEquals(listOf("task1", "timer100", "timer500", "follow-up"), ran)
        assertEquals(listOf("task", "timer@100", "timer@500", "task"), steps.trace)
    }

    @Test fun cancelledTimersNeverFire() {
        val steps = StepExecutor(ManualClock())
        var fired = false
        steps.schedule(10) { fired = true }.cancel()
        assertEquals(0, steps.timerCount)
        steps.advance(100)
        assertFalse(fired)
    }

    @Test fun interleaveRunsEveryOrderingAndNamesTheFailingOne() {
        val seen = ArrayList<String>()
        val n = StepExecutor.interleave(
            actions = listOf<Pair<String, (MutableList<String>) -> Unit>>("a" to { it += "a" }, "b" to { it += "b" }, "c" to { it += "c" }),
            fresh = { ArrayList() },
        ) { seen += it.joinToString("") }
        assertEquals(6, n)
        assertEquals(setOf("abc", "acb", "bac", "bca", "cab", "cba"), seen.toSet())
        val e = runCatching {
            StepExecutor.interleave(listOf<Pair<String, (MutableList<String>) -> Unit>>("a" to { it += "a" }, "b" to { it += "b" }), { ArrayList() }) {
                check(it.joinToString("") != "ba") { "b before a" }
            }
        }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("[b, a]"))
    }

    @Test fun fakeHostDeathFailsOutstandingAndFutureCallsThenReviveStartsANewGeneration() {
        val host = FakeHost()
        val tag = CallTag("r1", "L", "ask")
        host.hangOn = { it is EngineOp.Ask }
        val f = pool.submit<EngineReply> { host.call(EngineOp.Ask(tag, 1, "s", "u", null, 0, 0.0, 1)) }
        host.awaitInFlight(1)
        var died = -1
        host.onDied = { died = it }
        host.die()
        val cause = runCatching { f.get(5, TimeUnit.SECONDS) }.exceptionOrNull()!!.cause
        assertTrue(cause is DeadObjectException)
        assertEquals(1, died)
        assertTrue(runCatching { host.call(EngineOp.Status(tag)) }.exceptionOrNull() is DeadObjectException)
        host.revive()
        assertEquals(2, host.gen)
        assertTrue(host.call(EngineOp.Status(tag)) is EngineReply.HostStatus)
    }

    @Test fun fakeBindingRoutedToAFakeHostTurnsHostDeathIntoEngineDied() {
        val rig = Rig()
        val host = FakeHost()
        rig.binding.useHost(host)
        val h = rig.open("CPU").handle
        host.hangOn = { it is EngineOp.Ask }
        val f = pool.submit<EngineReply> { rig.conn.call(EngineOp.Ask(rig.tag("ask"), h, "s", "u", null, 0, 0.0, 1)) }
        host.awaitInFlight(1)
        host.die()
        val e = runCatching { f.get(5, TimeUnit.SECONDS) }.exceptionOrNull()!!.cause
        assertTrue("EngineDied expected, got $e", e is EngineDied)
        assertEquals(EngineRole.BATCH, (e as EngineDied).role)
        assertEquals(1, rig.log.count(EngineEvents.DIED))
        // The next call binds generation 2 and revives the host.
        rig.open("CPU")
        assertEquals(2, host.gen)
    }

    @Test fun fakeBindingFiresAnyCallbackOnAnOldGeneration() {
        val rig = Rig()
        rig.open()
        rig.binding.die(1, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        rig.open()
        val before = rig.log.count(EngineEvents.STALE_CALLBACK)
        rig.binding.fire(1, "disconnected")
        rig.binding.fire(1, "binderDied")
        assertTrue("stale callbacks are logged", rig.log.count(EngineEvents.STALE_CALLBACK) > before)
        assertEquals(1, rig.log.count(EngineEvents.DIED))
    }

    @Test fun stepExecutorDrivesTheIdleTimerOfARealConnection() {
        val time = ManualTime()
        val steps = StepExecutor(time)
        val rig = Rig(tuning = EngineTuning(idleMs = 1_000), time = time, scheduler = steps)
        rig.open()
        assertTrue("an idle timer is pending", steps.timerCount >= 1)
        assertTrue(rig.binding.shutdowns.isEmpty())
        steps.advance(1_001)
        assertEquals(listOf(1), rig.binding.shutdowns.map { it.first })
    }
}
