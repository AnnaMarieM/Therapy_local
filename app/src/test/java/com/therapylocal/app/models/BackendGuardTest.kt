package com.therapylocal.app.models

import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendGuard.LastExit
import com.therapylocal.app.models.BackendGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendGuardTest {
    private class MapStore : BackendGuard.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val store = MapStore()
    private var clock = 42L
    private fun guard(build: String = "1/100/fp") = BackendGuard(store, build) { clock }.also { it.recover(null) }

    private val killedForMemory = LastExit(ExitKind.KILLED_FOR_MEMORY, foreground = true, "lmkd")
    private val userSwipe = LastExit(ExitKind.BENIGN, foreground = false, "user")

    /** A process that dies with the attempt open: the next start finds the note. */
    private fun dieWhileLoading(build: String = "1/100/fp", exit: LastExit? = killedForMemory) {
        guard(build).attempting("gemma", "GPU")
        BackendGuard(store, build) { clock }.recover(exit)
    }

    @Test
    fun anAttemptIsWrittenBeforeAndProvenAfter() {
        val g = guard()
        g.attempting("gemma", "GPU")
        assertEquals(State.ATTEMPTING, g.record("gemma", "GPU")!!.state)
        assertEquals("attempting", g.record("gemma", "GPU")!!.reason)
        g.proven("gemma", "GPU")
        val r = g.record("gemma", "GPU")!!
        assertEquals(State.PROVEN, r.state)
        assertTrue(r.everProven)
        assertEquals("proven on this device", r.reason)
    }

    @Test
    fun aNeverProvenBackendIsBlockedAfterTwoDeathsWhileLoading() {
        dieWhileLoading()
        assertEquals(State.DIED, guard().record("gemma", "GPU")!!.state)
        assertFalse(guard().blocked("gemma", "GPU"))
        dieWhileLoading()
        assertTrue(guard().blocked("gemma", "GPU"))
    }

    @Test
    fun aProvenBackendNeedsThreeStrikesAndACleanUnloadClearsThem() {
        val g = guard()
        g.attempting("gemma", "GPU"); g.proven("gemma", "GPU"); g.loaded("gemma", "GPU"); g.unloaded("gemma", "GPU")
        dieWhileLoading()
        dieWhileLoading()
        assertFalse(guard().blocked("gemma", "GPU"))
        assertEquals(2, guard().record("gemma", "GPU")!!.strikes)

        guard().apply { attempting("gemma", "GPU"); proven("gemma", "GPU"); loaded("gemma", "GPU") }
        assertEquals("a load alone is not surviving the work", 2, guard().record("gemma", "GPU")!!.strikes)
        guard().unloaded("gemma", "GPU")
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)

        repeat(3) { dieWhileLoading() }
        assertTrue(guard().blocked("gemma", "GPU"))
    }

    @Test
    fun aKillWhileTheGpuEngineIsLoadedInTheForegroundIsAStrike() {
        val g = guard()
        g.attempting("gemma", "GPU"); g.proven("gemma", "GPU"); g.loaded("gemma", "GPU")
        val events = BackendGuard(store, "1/100/fp").recover(killedForMemory)
        assertEquals(listOf("backend_died_while_loaded"), events.map { it.evt })
        assertEquals(1, guard().record("gemma", "GPU")!!.strikes)
    }

    @Test
    fun aBackgroundKillOrAUserSwipeIsNotTheBackendsFault() {
        val g = guard()
        g.attempting("gemma", "GPU"); g.proven("gemma", "GPU"); g.loaded("gemma", "GPU")
        BackendGuard(store, "1/100/fp").recover(LastExit(ExitKind.KILLED_FOR_MEMORY, foreground = false, "cached"))
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)

        dieWhileLoading(exit = userSwipe)
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)
    }

    @Test
    fun aCaughtLoadFailureIsAStrikeButAnInferenceErrorIsOnlyNoted() {
        val g = guard()
        g.attempting("emb", "GPU")
        assertEquals(State.FAILED, g.failed("emb", "GPU", "OpenCL error").state)
        assertEquals(1, g.record("emb", "GPU")!!.strikes)

        g.attempting("gemma", "GPU"); g.proven("gemma", "GPU")
        g.inferenceFailed("gemma", "GPU", "exceeds maximum")
        val r = g.record("gemma", "GPU")!!
        assertEquals(State.PROVEN, r.state)
        assertEquals(0, r.strikes)
        assertTrue(r.reason.contains("exceeds maximum"))
    }

    @Test
    fun theCpuIsNeverBlocked() {
        val g = guard()
        repeat(5) { g.attempting("gemma", "CPU"); g.failed("gemma", "CPU", "boom") }
        assertFalse(g.blocked("gemma", "CPU"))
    }

    @Test
    fun anAppOrSystemUpdateForgetsEverything() {
        dieWhileLoading(); dieWhileLoading()
        assertTrue(guard().blocked("gemma", "GPU"))
        val events = BackendGuard(store, "1/200/fp").recover(null)
        assertEquals(listOf("backend_guard_reset_new_build"), events.map { it.evt })
        assertNull(BackendGuard(store, "1/200/fp").record("gemma", "GPU"))
    }

    @Test
    fun modelsAndBackendsAreKeptApart() {
        dieWhileLoading(); dieWhileLoading()
        val g = guard()
        assertTrue(g.blocked("gemma", "GPU"))
        assertFalse(g.blocked("gemma", "NPU"))
        assertFalse(g.blocked("gemma+audio", "GPU"))
    }

    @Test
    fun twoLoadsOpenAtOnceAreBothRememberedSoAKillDuringEitherCounts() {
        val g = guard()
        g.attempting("gemma", "GPU")
        g.attempting("embed", "GPU")
        g.proven("embed", "GPU") // the embedder finished first: the LLM's attempt must still be open
        val events = BackendGuard(store, "1/100/fp").recover(killedForMemory)
        assertEquals(listOf("gemma" to "GPU"), events.filter { it.evt == "backend_died_while_loading" }.map { it.model to it.backend })
        assertEquals(1, guard().record("gemma", "GPU")!!.strikes)
        assertEquals(0, guard().record("embed", "GPU")!!.strikes)
    }

    @Test
    fun aKillIsReportedWithTheNativeStepItHappenedIn() {
        val g = guard()
        g.attempting("gemma", "GPU")
        g.phase("gemma", "GPU", "initialize")
        val e = BackendGuard(store, "1/100/fp").recover(killedForMemory).single()
        assertEquals("phase=initialize", e.detail)
        assertTrue(guard().record("gemma", "GPU")!!.reason.contains("in initialize"))
    }

    @Test
    fun aBlockOnAProvenBackendGetsOneMoreTryAWeekLater() {
        val g = guard()
        g.attempting("gemma", "GPU"); g.proven("gemma", "GPU"); g.loaded("gemma", "GPU"); g.unloaded("gemma", "GPU")
        repeat(BackendGuard.BLOCK_PROVEN) { dieWhileLoading() }
        assertTrue(guard().blocked("gemma", "GPU"))
        clock += BackendGuard.BLOCK_RETRY_MS
        assertFalse(guard().blocked("gemma", "GPU"))
        dieWhileLoading()
        assertTrue("failing the retry blocks it again", guard().blocked("gemma", "GPU"))
    }

    @Test
    fun aNeverProvenBlockDoesNotExpire() {
        repeat(BackendGuard.BLOCK_UNPROVEN) { dieWhileLoading() }
        clock += 10 * BackendGuard.BLOCK_RETRY_MS
        assertTrue(guard().blocked("gemma", "GPU"))
    }

    @Test
    fun testingEverythingAgainKeepsAnAttemptInFlightSoAKillRightAfterStillCounts() {
        val g = guard()
        g.attempting("gemma", "GPU")
        g.reset()
        assertTrue(BackendGuard(store, "1/100/fp").recover(killedForMemory).any { it.evt == "backend_died_while_loading" })
    }

    @Test
    fun aLoadThatFoundNoBackendShowsAsNoneAndASkipIsOnlyWrittenWhenItsReasonChanges() {
        val g = guard()
        g.selectedNone("gemma")
        assertEquals(BackendGuard.NONE, g.overview().single().selected)
        var writes = 0
        val counting = object : BackendGuard.Store by store {
            override fun put(key: String, value: String?) { writes++; store.put(key, value) }
        }
        val c = BackendGuard(counting, "1/100/fp") { clock }
        repeat(10) { c.skipped("gemma", "NPU", "dispatch library missing") }
        assertEquals(1, writes)
        c.skipped("gemma", "NPU", "something else")
        assertEquals(2, writes)
    }
}
