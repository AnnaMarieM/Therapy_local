package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendGuard.ExitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AD-04: a host that crashes ends itself with EXIT_SELF (13, or 100 + signal). It must read as the crash it is and be struck like one. */
class QuietCrashExitTest {
    private fun rec(status: Int, reason: Int = ApplicationExitInfo.REASON_EXIT_SELF) =
        ExitRecord(reason, status, 1000, "pkg:batch", 7, 100, 0, 0, null)

    @Test fun nativeStatusesMapToANativeCrashWithTheSignal() {
        for (sig in listOf(4, 5, 6, 7, 8, 11, 31)) {
            val r = rec(HostExit.NATIVE_BASE + sig)
            assertEquals(ExitKind.NATIVE_CRASH, ExitReasons.map(r, true).kind)
            assertEquals("signal $sig", ExitReasons.describe(r))
        }
    }

    @Test fun javaCrashIsAPlainCrashNotNativeAndNotBenign() {
        val r = rec(HostExit.JAVA_CRASH.code)
        assertEquals(ExitKind.CRASH, ExitReasons.map(r, true).kind)
        assertEquals("java_crash", ExitReasons.describe(r))
    }

    @Test fun benignCodesAreUnchanged() {
        assertEquals(listOf(10, 11, 12), HostExit.entries.filter { it.benign }.map { it.code })
        for (h in HostExit.entries.filter { it.benign }) {
            val m = ExitReasons.map(rec(h.code), false)
            assertEquals(ExitKind.BENIGN, m.kind)
            assertEquals(h.why, m.why)
        }
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(0), false).kind)
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(99), false).kind)
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(14), false).kind)
    }

    @Test fun nativeRangeIsOnlyForExitSelfAndHasEdges() {
        assertNull(HostExit.nativeSignal(100))
        assertEquals(1, HostExit.nativeSignal(101))
        assertEquals(64, HostExit.nativeSignal(164))
        assertNull(HostExit.nativeSignal(165))
        assertNull(HostExit.nativeSignal(13))
        // a real SIGSEGV record (the handler did not run) is still what it was
        assertEquals(ExitKind.NATIVE_CRASH, ExitReasons.map(rec(11, ApplicationExitInfo.REASON_CRASH_NATIVE), true).kind)
        // status 111 under another reason is not a quiet crash
        assertEquals(ExitKind.UNKNOWN, ExitReasons.map(rec(111, ApplicationExitInfo.REASON_OTHER), true).kind)
        assertEquals("crash_native status=11", ExitReasons.describe(rec(11, ApplicationExitInfo.REASON_CRASH_NATIVE)))
    }

    @Test fun connectionClassifiesAQuietNativeCrashLikeANativeCrash() {
        val r = Rig()
        r.binding.script = { p, op ->
            if (op is EngineOp.Open) {
                r.binding.die(p, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.NATIVE_BASE + 11)
                throw BindingFailure(BindingFailure.Kind.DEATH)
            }
            null
        }
        val d = try { r.open("GPU"); null } catch (e: EngineDied) { e }!!
        assertEquals(ExitKind.NATIVE_CRASH, d.exit.kind)
        assertEquals("signal 11", d.exit.description)
        val died = r.log.all(EngineEvents.DIED).single()
        assertEquals("native_crash", died.f["exit_kind"])
        assertEquals("signal 11", died.f["description"])
        assertEquals(1, r.log.count(EngineEvents.DIED))
        assertEquals(0, r.log.count(EngineEvents.EXIT)) // not a benign end
    }

    @Test fun connectionClassifiesAJavaCrashAsCrashAndShutdownAsBenign() {
        val r = Rig()
        r.open()
        r.binding.die(1, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.JAVA_CRASH.code)
        val died = r.log.all(EngineEvents.DIED).single()
        assertEquals("crash", died.f["exit_kind"])
        val b = Rig()
        b.open()
        b.binding.die(1, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.SHUTDOWN.code)
        assertEquals(0, b.log.count(EngineEvents.DIED))
    }

    @Test fun aQuietNativeCrashIsStruckExactlyLikeANativeCrash() {
        val store = object : BackendGuard.Store {
            val m = HashMap<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
            override fun keys(): Set<String> = m.keys.toSet()
        }
        val guard = BackendGuard(store, "1/100/fp").also { it.recover(null) }
        val exit = BackendGuard.LastExit(ExitReasons.map(rec(HostExit.NATIVE_BASE + 11), true).kind, true, ExitReasons.describe(rec(HostExit.NATIVE_BASE + 11)))
        repeat(2) {
            guard.attempting("m", "GPU", "batch")
            assertTrue(guard.died("m", "GPU", "initialize", exit).struck)
        }
        assertTrue(guard.blocked("m", "GPU"))
        // the start-up path: the record of the last process, found by the next start
        val next = BackendGuard(store, "1/100/fp")
        guard.attempting("n", "GPU")
        next.recover(exit)
        assertEquals(1, BackendGuard(store, "1/100/fp").record("n", "GPU")!!.strikes)
        // a Java crash is a CRASH: no strike of its own, as before
        val java = BackendGuard.LastExit(ExitReasons.map(rec(HostExit.JAVA_CRASH.code), true).kind, true, "java_crash")
        guard.attempting("j", "CPU", "batch")
        assertFalse(java.kind == ExitKind.NATIVE_CRASH)
    }
}
