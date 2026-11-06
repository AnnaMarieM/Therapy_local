package com.therapylocal.app.models

import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendGuard.LastExit
import com.therapylocal.app.models.BackendGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N32: a guard entry names the process it was noted for, and the next start judges it by that process's own exit. */
class BackendRoleRecoveryTest {
    private val store = SharedStore()
    private val build = "1/100/fp"
    private fun exit(kind: ExitKind, foreground: Boolean = true) = LastExit(kind, foreground, "test ${kind.name}")
    private fun start(main: LastExit?, host: (String, Long) -> LastExit? = { _, _ -> null }) = BackendGuard(store, build).let { it.recover(main, host) }
    private fun guard() = BackendGuard(store, build)

    @Test
    fun aBatchLoadIsNotStruckBecauseMainWasKilled() {
        start(null)
        guard().attempting("m", "GPU", "batch")
        // Vault.lock kills main with SIGNALED 9, which reads as a memory kill; the :batch host ended by itself (halt 10)
        val ev = start(exit(ExitKind.KILLED_FOR_MEMORY)) { role, _ -> if (role == "batch") exit(ExitKind.BENIGN) else null }
        assertEquals(listOf("backend_attempt_interrupted"), ev.map { it.evt })
        assertTrue(ev.single().detail!!.contains("not counted"))
        assertEquals(null, guard().record("m", "GPU")?.takeIf { it.strikes > 0 })
    }

    @Test
    fun aBatchLoadIsStruckWhenTheBatchProcessItselfWasKilledForMemory() {
        start(null)
        guard().attempting("m", "GPU", "batch")
        var askedRole: String? = null
        var askedSince = -1L
        val ev = start(null) { role, since -> askedRole = role; askedSince = since; exit(ExitKind.KILLED_FOR_MEMORY) }
        assertEquals("batch", askedRole)
        assertTrue("the lookup is bounded by the time the entry was noted", askedSince > 0)
        assertEquals(State.DIED, guard().record("m", "GPU")!!.state)
        assertEquals("backend_died_while_loading", ev.single().evt)
    }

    @Test
    fun aMainEntryIsStillJudgedByMainsExit() {
        start(null)
        guard().attempting("m", "GPU")
        start(exit(ExitKind.NATIVE_CRASH)) { _, _ -> error("no host record is asked for a main entry") }
        assertEquals(State.DIED, guard().record("m", "GPU")!!.state)
    }

    @Test
    fun aHostEntryWithNoRecordFollowsTheUnknownRule() {
        start(null)
        repeat(BackendGuard.INTERRUPTED_STRIKE) {
            guard().attempting("m", "GPU", "live")
            start(exit(ExitKind.NATIVE_CRASH)) // main's exit says nothing about :live
            if (it < BackendGuard.INTERRUPTED_STRIKE - 1) assertEquals(0, guard().record("m", "GPU")?.strikes ?: 0)
        }
        assertEquals(1, guard().record("m", "GPU")!!.strikes)
    }

    @Test
    fun anEntryWrittenBeforeRolesExistedReadsAsMain() {
        start(null)
        store.map["pending"] = "m@GPU#initialize,n@CPU"
        store.map["resident"] = "k@NPU"
        val ev = start(exit(ExitKind.NATIVE_CRASH)) { _, _ -> error("old entries are main entries") }
        assertEquals(setOf("m" to "GPU", "n" to "CPU", "k" to "NPU"), ev.map { it.model to it.backend }.toSet())
        assertEquals(State.DIED, guard().record("m", "GPU")!!.state)
        assertTrue(ev.first { it.model == "m" }.detail!!.contains("initialize"))
    }

    @Test
    fun aResidentHostEngineIsJudgedByItsHostAndNotByMain() {
        start(null)
        guard().apply { attempting("m", "GPU", "live"); proven("m", "GPU"); loaded("m", "GPU", "live") }
        assertTrue(start(exit(ExitKind.NATIVE_CRASH)) { _, _ -> exit(ExitKind.BENIGN) }.isEmpty())
        assertEquals(0, guard().record("m", "GPU")!!.strikes)
        guard().apply { attempting("m", "GPU", "live"); proven("m", "GPU"); loaded("m", "GPU", "live") }
        val ev = start(null) { role, _ -> if (role == "live") exit(ExitKind.NATIVE_CRASH) else null }
        assertEquals("backend_died_while_loaded", ev.single().evt)
    }

    @Test
    fun theRoleAndTheTimeSurviveEveryPhaseNote() {
        start(null)
        guard().attempting("m", "GPU", "batch")
        guard().phase("m", "GPU", "create")
        guard().phase("m", "GPU", "initialize")
        var since = 0L
        start(null) { _, s -> since = s; null }
        assertTrue(since > 0)
        assertTrue(store.map["pending"] == null)
    }
}
