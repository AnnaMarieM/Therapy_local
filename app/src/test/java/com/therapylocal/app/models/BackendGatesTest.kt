package com.therapylocal.app.models

import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendGuard.LastExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conceptual backend policy (no device names, no RAM tiers): a GPU that takes its host down is struck, falls through to the CPU in the
 * same load, is not tried again in this run and is blocked after the threshold. These are the facts the removal of the Mali/tier gate rests on.
 */
class BackendGatesTest {
    private val store = SharedStore()
    private val guard = BackendGuard(store, "1/100/fp").also { it.recover(null) }
    private val killed = LastExit(ExitKind.KILLED_FOR_MEMORY, true, "lmkd")
    private val model = "embeddinggemma-2-740m+audio"

    private fun ladder() = BackendLadder(guard, role = "live")

    @Test
    fun aKillDuringTheGpuLoadFallsThroughToTheCpuInTheSameLoadAndStrikesTheGpu() {
        val tried = ArrayList<String>()
        val r = ladder().load(model, listOf("GPU", "CPU"), open = { b ->
            tried += b
            if (b == "GPU") throw EngineDied(EngineRole.LIVE, 1, killed, "initialize", "open")
            b
        }, close = {})
        assertEquals("CPU", r.backend)
        assertEquals(listOf("GPU", "CPU"), tried)
        assertEquals(1, guard.record(model, "GPU")!!.strikes)
    }

    @Test
    fun aKillWhileLoadedStrikesAndTheNextLoadOfTheRunSkipsTheGpu() {
        ladder().load(model, listOf("GPU", "CPU"), open = { it }, close = {})
        guard.diedWhileLoaded(model, "GPU", killed)
        assertEquals(1, guard.record(model, "GPU")!!.strikes)
        val tried = ArrayList<String>()
        val r = ladder().load(model, listOf("GPU", "CPU"), open = { tried += it; it }, close = {})
        assertEquals("CPU", r.backend)
        assertEquals("the demoted GPU is not even tried", listOf("CPU"), tried)
    }

    @Test
    fun aDeathWhileLoadingDemotesTheGpuForTheRestOfTheRun() {
        guard.attempting(model, "GPU", "live")
        guard.died(model, "GPU", "test", killed)
        assertTrue(guard.demoted(model, "GPU"))
    }

    /**
     * Replaces the timed "CPU only for 5 minutes" after RUNNING_CRITICAL. Two parts: the killed GPU is demoted (here, even though the memory
     * gate would now pass), and a pressure without a kill is covered by the gate reading the free memory at the next load (MemoryGateTest).
     */
    @Test
    fun afterAMemoryPressureKillTheReloadDoesNotGoStraightBackOntoTheGpu() {
        ladder().load(model, listOf("GPU", "CPU"), open = { it }, close = {})
        guard.diedWhileLoaded(model, "GPU", killed)
        val gateSaysFine: (String) -> String? = { null }
        val r = ladder().load(model, listOf("GPU", "CPU"), gate = gateSaysFine, open = { it }, close = {})
        assertEquals("CPU", r.backend)
    }

    @Test
    fun theGpuIsBlockedAfterTwoStrikesWhenNeverProvenAndTheCpuNever() {
        repeat(2) { guard.attempting(model, "GPU", "live"); guard.died(model, "GPU", "test", killed) }
        assertTrue(guard.blocked(model, "GPU"))
        assertFalse(guard.blocked(model, "CPU"))
    }

    @Test
    fun resetForgetsTheDemotionToo() {
        guard.diedWhileLoaded(model, "GPU", killed)
        assertTrue(guard.demoted(model, "GPU"))
        guard.reset()
        assertFalse(guard.demoted(model, "GPU"))
    }

    @Test
    fun theCpuIsNeverDemotedByADeath() {
        guard.diedWhileLoaded(model, "CPU", killed)
        assertFalse(guard.demoted(model, "CPU"))
    }
}
