package com.therapylocal.app.models

import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.engine.ReplyTooLarge
import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendGuard.LastExit
import com.therapylocal.app.models.BackendGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The immediate strikes (N04, N08, N33): what a host death does to the guard online, and that the next start never counts it twice. */
class BackendEngineDeathTest {
    private val store = SharedStore()
    private val build = "1/100/fp"
    private val guard = BackendGuard(store, build).also { it.recover(null) }
    private fun exit(kind: ExitKind, foreground: Boolean = true, d: String = "d") = LastExit(kind, foreground, d)
    private fun nextStart(main: LastExit?, host: (String, Long) -> LastExit? = { _, _ -> null }) = BackendGuard(store, build).recover(main, host)

    @Test
    fun aDeathWhileLoadingStrikesAtOnceWithTheExitKindAndClearsThePendingNote() {
        guard.attempting("m", "GPU", "batch")
        guard.phase("m", "GPU", "initialize")
        val d = guard.died("m", "GPU", null, exit(ExitKind.NATIVE_CRASH, d = "crash_native status=11"))
        assertTrue(d.struck)
        val r = guard.record("m", "GPU")!!
        assertEquals(State.DIED, r.state)
        assertEquals(1, r.strikes)
        assertEquals("process died while loading, in initialize (native_crash: crash_native status=11)", r.reason)
        // the next start has nothing left to count, whatever the exit of main was
        assertTrue(nextStart(exit(ExitKind.KILLED_FOR_MEMORY)).isEmpty())
        assertEquals(1, BackendGuard(store, build).record("m", "GPU")!!.strikes)
    }

    @Test
    fun theSecondCrashBlocksTheGpuAndTheCpuIsNeverBlocked() {
        repeat(2) { guard.attempting("m", "GPU", "batch"); guard.died("m", "GPU", "create", exit(ExitKind.NATIVE_CRASH)) }
        assertTrue(guard.blocked("m", "GPU"))
        repeat(3) { guard.attempting("m", "CPU", "batch"); guard.died("m", "CPU", "create", exit(ExitKind.NATIVE_CRASH)) }
        assertFalse(guard.blocked("m", "CPU"))
    }

    @Test
    fun aPlainCrashOrAnUnknownReasonCountsOnlyTheSecondTimeInARowLikeTheNextStartDoes() {
        for (k in listOf(ExitKind.CRASH, ExitKind.UNKNOWN)) {
            store.map.clear()
            val g = BackendGuard(store, build).also { it.recover(null) }
            g.attempting("m", "GPU", "batch")
            val first = g.died("m", "GPU", "test", exit(k))
            assertFalse("for $k", first.struck)
            assertEquals("for $k", 0, g.record("m", "GPU")?.strikes ?: 0)
            assertTrue("the pending note goes either way", BackendGuard(store, build).recover(null).isEmpty())
            g.attempting("m", "GPU", "batch")
            assertTrue("for $k", g.died("m", "GPU", "test", exit(k)).struck)
            assertEquals("for $k", State.FAILED, g.record("m", "GPU")!!.state)
        }
    }

    @Test
    fun aProvenLoadResetsTheRunOfPlainCrashes() {
        guard.attempting("m", "GPU", "batch")
        assertFalse(guard.died("m", "GPU", "test", exit(ExitKind.UNKNOWN)).struck)
        guard.attempting("m", "GPU", "batch")
        guard.proven("m", "GPU")
        guard.attempting("m", "GPU", "batch")
        assertFalse(guard.died("m", "GPU", "test", exit(ExitKind.CRASH)).struck)
    }

    @Test
    fun anEndTheAppOrTheHostChoseIsNeverAStrike() {
        guard.attempting("m", "GPU", "batch")
        val d = guard.died("m", "GPU", "create", exit(ExitKind.BENIGN, d = "exit_self status=11"))
        assertFalse(d.struck)
        assertNull(guard.record("m", "GPU")?.takeIf { it.strikes > 0 })
        assertTrue(nextStart(null).isEmpty()) // and the pending note is gone
    }

    @Test
    fun aHangIsStruckLikeADriverHang() {
        guard.attempting("m", "GPU", "batch")
        guard.died("m", "GPU", "initialize", exit(ExitKind.ANR, d = "hang: open exceeded 300000 ms"))
        assertEquals(State.DIED, guard.record("m", "GPU")!!.state)
    }

    @Test
    fun threeCpuMemoryKillsAreTooLargeAndNativeCrashesAreNot() {
        assertFalse(guard.tooLarge("m"))
        repeat(BackendGuard.BLOCK_UNPROVEN) { i ->
            guard.attempting("m", "CPU", "batch")
            val d = guard.died("m", "CPU", "create", exit(ExitKind.KILLED_FOR_MEMORY))
            assertEquals("too large only once it is clear", i + 1 >= BackendGuard.BLOCK_UNPROVEN, d.tooLarge != null)
        }
        assertTrue(guard.tooLarge("m"))

        repeat(5) { guard.attempting("c", "CPU", "batch"); guard.died("c", "CPU", "create", exit(ExitKind.NATIVE_CRASH)) }
        assertFalse("native crashes are a bug, not a size", guard.tooLarge("c"))
        // a mix does not reach the bar on crashes alone
        guard.attempting("x", "CPU", "batch"); guard.died("x", "CPU", "create", exit(ExitKind.KILLED_FOR_MEMORY))
        guard.attempting("x", "CPU", "batch"); guard.died("x", "CPU", "create", exit(ExitKind.NATIVE_CRASH))
        assertFalse(guard.tooLarge("x"))
        guard.reset()
        assertFalse("a manual reset forgets it", guard.tooLarge("m"))
    }

    @Test
    fun tooLargeSurvivesTheNextStartAndTheStartCountsTheSameKillsToo() {
        guard.attempting("m", "CPU", "batch"); guard.died("m", "CPU", "create", exit(ExitKind.KILLED_FOR_MEMORY))
        // one more kill that nobody caught online: the next start counts it
        guard.attempting("m", "CPU", "batch")
        val ev = nextStart(null) { role, _ -> if (role == "batch") exit(ExitKind.KILLED_FOR_MEMORY) else null }
        assertTrue(ev.any { it.evt == "model_too_large_for_device" })
        assertTrue(BackendGuard(store, build).tooLarge("m"))
    }

    // ---- diedWhileLoaded ------------------------------------------------------------------------------------

    @Test
    fun aHostDeathWhileAGpuEngineIsLoadedStrikesOnceAndTheNextStartDoesNotCountItAgain() {
        guard.attempting("m", "GPU", "live"); guard.proven("m", "GPU"); guard.loaded("m", "GPU", "live")
        val ev = guard.diedWhileLoaded("m", "GPU", exit(ExitKind.NATIVE_CRASH))
        assertEquals(listOf("backend_died_while_loaded"), ev.map { it.evt })
        assertEquals(1, guard.record("m", "GPU")!!.strikes)
        // main is killed afterwards, as natively as you like: the resident note is gone, so there is no second strike
        assertTrue(nextStart(exit(ExitKind.NATIVE_CRASH)).isEmpty())
        assertEquals(1, BackendGuard(store, build).record("m", "GPU")!!.strikes)
    }

    @Test
    fun aForegroundMemoryKillOfAResidentEngineStrikesAndABackgroundOneDoesNot() {
        guard.attempting("m", "GPU", "batch"); guard.proven("m", "GPU"); guard.loaded("m", "GPU", "batch")
        assertEquals(1, guard.diedWhileLoaded("m", "GPU", exit(ExitKind.KILLED_FOR_MEMORY, foreground = true)).size)
        guard.attempting("n", "GPU", "batch"); guard.proven("n", "GPU"); guard.loaded("n", "GPU", "batch")
        assertTrue(guard.diedWhileLoaded("n", "GPU", exit(ExitKind.KILLED_FOR_MEMORY, foreground = false)).isEmpty())
        assertEquals(0, guard.record("n", "GPU")!!.strikes)
        assertTrue(nextStart(exit(ExitKind.KILLED_FOR_MEMORY)).isEmpty()) // and n is not resident any more
    }

    @Test
    fun aBenignEndIsSettledWithUnloadedAndNeverBlamesTheGpu() {
        guard.attempting("m", "GPU", "live"); guard.proven("m", "GPU"); guard.loaded("m", "GPU", "live")
        guard.unloaded("m", "GPU")
        assertTrue(nextStart(exit(ExitKind.NATIVE_CRASH)).isEmpty())
        assertEquals(0, BackendGuard(store, build).record("m", "GPU")!!.strikes)
        // even handed a benign exit by mistake, diedWhileLoaded strikes nothing
        guard.loaded("m", "GPU", "live")
        assertTrue(guard.diedWhileLoaded("m", "GPU", exit(ExitKind.BENIGN)).isEmpty())
        assertEquals(0, guard.record("m", "GPU")!!.strikes)
    }

    @Test
    fun aCpuEngineKilledForMemoryWhileLoadedCountsTowardsTooLarge() {
        guard.diedWhileLoaded("m", "CPU", exit(ExitKind.KILLED_FOR_MEMORY))
        assertFalse(guard.tooLarge("m"))
        guard.diedWhileLoaded("m", "CPU", exit(ExitKind.KILLED_FOR_MEMORY))
        assertTrue(guard.tooLarge("m"))
        // a native crash of a loaded CPU engine says nothing about size
        guard.diedWhileLoaded("c", "CPU", exit(ExitKind.NATIVE_CRASH)); guard.diedWhileLoaded("c", "CPU", exit(ExitKind.NATIVE_CRASH))
        assertFalse(guard.tooLarge("c"))
    }

    @Test
    fun anAttemptThatNeverReachedADeviceLeavesNoNoteAndNoStrike() {
        guard.attempting("m", "GPU", "batch")
        guard.abandoned("m", "GPU")
        assertTrue(nextStart(null).isEmpty())
        repeat(3) { guard.attempting("m", "GPU", "batch"); guard.abandoned("m", "GPU"); nextStart(null) }
        assertEquals(0, BackendGuard(store, build).record("m", "GPU")!!.strikes)
    }

    // ---- the typed errors the ladder meets ------------------------------------------------------------------

    @Test
    fun theLadderStrikesADeathOnceAndTriesTheNextBackend() {
        val tape = LogTape()
        val d = EngineDied(EngineRole.BATCH, 3, exit(ExitKind.NATIVE_CRASH, d = "crash_native status=11"), "initialize", "open")
        var gpuTries = 0
        val r = BackendLadder(guard, tape, role = "batch").load("m", listOf("GPU", "CPU"), open = { b -> if (b == "GPU") { gpuTries++; throw d } else b }, close = {})
        assertEquals("CPU", r.backend)
        assertEquals(1, gpuTries)
        assertEquals(State.DIED, guard.record("m", "GPU")!!.state)
        assertEquals(1, guard.record("m", "GPU")!!.strikes)
        val failed = tape.of("backend_failed").single()
        assertEquals(true, failed["engine_died"])
        assertEquals("native_crash", failed["exit_kind"])
        assertEquals("crash_native status=11", failed["exit_description"])
        assertEquals(3, failed["gen"])
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        // one load_id, and the next start has nothing to count
        assertTrue(nextStart(exit(ExitKind.NATIVE_CRASH)).isEmpty())
    }

    @Test
    fun aHangKillIsLoggedAsAHang() {
        val tape = LogTape()
        val d = EngineDied(EngineRole.BATCH, 1, exit(ExitKind.ANR, d = "hang: open exceeded 300000 ms"), "initialize", "open")
        BackendLadder(guard, tape).load("m", listOf("GPU", "CPU"), open = { b -> if (b == "GPU") throw d else b }, close = {})
        assertEquals(true, tape.of("backend_failed").single()["hang"])
    }

    @Test
    fun aCaughtFailureIsStillAFailureAndNotADeath() {
        val tape = LogTape()
        BackendLadder(guard, tape).load("m", listOf("GPU", "CPU"), open = { b -> if (b == "GPU") throw IllegalStateException("x") else b }, close = {})
        assertNull(tape.of("backend_failed").single()["engine_died"])
        assertEquals(State.FAILED, guard.record("m", "GPU")!!.state)
    }

    @Test
    fun noHostThatCanServeIsNeverAStrikeAndTheLoadStopsAtOnce() {
        for (t in listOf<Throwable>(EngineUnavailable(EngineRole.BATCH, "storm", 1000), ReplyTooLarge("open", 5_000_000))) {
            store.map.clear()
            val g = BackendGuard(store, build).also { it.recover(null) }
            val tape = LogTape()
            var calls = 0
            assertThrows(t.javaClass) {
                BackendLadder(g, tape, role = "batch").load("m", listOf("GPU", "CPU"), open = { calls++; throw t }, close = {})
            }
            assertEquals("the next backend is not tried: $t", 1, calls)
            assertEquals(0, g.record("m", "GPU")?.strikes ?: 0)
            assertNotNull(tape.of("backend_attempt_abandoned").singleOrNull())
            // the attempt note is gone: repeated starts never turn it into a strike
            repeat(3) { BackendGuard(store, build).recover(null) }
            assertEquals(0, BackendGuard(store, build).record("m", "GPU")?.strikes ?: 0)
        }
    }

    @Test
    fun aBenignEndStopsTheLadderWithoutAStrikeInsteadOfWalkingDownToTheCpu() {
        val d = EngineDied(EngineRole.LIVE, 2, exit(ExitKind.BENIGN, d = "exit_self status=12"), "create", "open")
        var cpu = false
        assertThrows(EngineDied::class.java) {
            BackendLadder(guard, LogTape(), role = "live").load("m", listOf("GPU", "CPU"), open = { b -> if (b == "GPU") throw d else { cpu = true; b } }, close = {})
        }
        assertFalse(cpu)
        assertEquals(0, guard.record("m", "GPU")?.strikes ?: 0)
        assertTrue(nextStart(null).isEmpty())
    }
}
