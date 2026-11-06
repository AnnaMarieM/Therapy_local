package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import com.therapylocal.app.models.BackendGuard.ExitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N18 (record selection) and N30/N34 (the reason mapping). */
class ExitReasonsTest {
    private fun rec(reason: Int, status: Int = 0, ts: Long = 1000, name: String = "pkg:batch", pid: Int = 7) =
        ExitRecord(reason, status, ts, name, pid, 100, 0, 0, null)

    @Test fun pickNeedsPidProcessNameAndATimestampNotBeforeTheBind() {
        val good = rec(ApplicationExitInfo.REASON_CRASH, ts = 2000)
        val all = listOf(
            rec(ApplicationExitInfo.REASON_LOW_MEMORY, ts = 500), // before the bind
            rec(ApplicationExitInfo.REASON_ANR, ts = 3000, name = "pkg"), // main's record
            rec(ApplicationExitInfo.REASON_ANR, ts = 3000, pid = 8), // another pid
            good,
        )
        assertEquals(good, ExitReasons.pick(all, 7, "pkg:batch", 1000))
        assertNull(ExitReasons.pick(all, 7, "pkg:batch", 2500))
        assertNull(ExitReasons.pick(all, 7, "pkg:live", 1000))
    }

    @Test fun haltCodesMapToBenignWithTheirReason() {
        for (h in HostExit.entries.filter { it.benign }) {
            val m = ExitReasons.map(rec(ApplicationExitInfo.REASON_EXIT_SELF, h.code), lowMemoryKillReports = false)
            assertEquals(ExitKind.BENIGN, m.kind)
            assertEquals(h.why, m.why)
        }
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(ApplicationExitInfo.REASON_EXIT_SELF, 0), true).kind)
    }

    @Test fun freezerIsBenignAndDependencyDiedIsUnknown() {
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(ApplicationExitInfo.REASON_FREEZER), true).kind)
        assertEquals("freezer", ExitReasons.map(rec(ApplicationExitInfo.REASON_FREEZER), true).why)
        assertEquals(ExitKind.UNKNOWN, ExitReasons.map(rec(ApplicationExitInfo.REASON_DEPENDENCY_DIED), true).kind)
    }

    @Test fun sigkillIsAMemoryKillOnlyWhereTheSystemDoesNotReportThem() {
        val k = rec(ApplicationExitInfo.REASON_SIGNALED, 9)
        assertEquals(ExitKind.KILLED_FOR_MEMORY, ExitReasons.map(k, lowMemoryKillReports = false).kind)
        assertEquals(ExitKind.UNKNOWN, ExitReasons.map(k, lowMemoryKillReports = true).kind)
        assertEquals(ExitKind.UNKNOWN, ExitReasons.map(rec(ApplicationExitInfo.REASON_SIGNALED, 11), false).kind)
    }

    @Test fun theRestKeepTodaysMapping() {
        assertEquals(ExitKind.KILLED_FOR_MEMORY, ExitReasons.map(rec(ApplicationExitInfo.REASON_LOW_MEMORY), true).kind)
        assertEquals(ExitKind.NATIVE_CRASH, ExitReasons.map(rec(ApplicationExitInfo.REASON_CRASH_NATIVE), true).kind)
        assertEquals(ExitKind.CRASH, ExitReasons.map(rec(ApplicationExitInfo.REASON_CRASH), true).kind)
        assertEquals(ExitKind.ANR, ExitReasons.map(rec(ApplicationExitInfo.REASON_ANR), true).kind)
        assertEquals(ExitKind.BENIGN, ExitReasons.map(rec(ApplicationExitInfo.REASON_USER_STOPPED), true).kind)
        assertEquals(ExitKind.UNKNOWN, ExitReasons.map(rec(ApplicationExitInfo.REASON_OTHER), true).kind)
    }

    @Test fun cpuTimeIsReadFromAProcStatLineWithSpacesInTheName() {
        val line = "1234 (my proc) S 1 1 1 0 -1 4194560 100 0 0 0 250 50 0 0 20 0 5 0 100 1000 100 18446744073709551615"
        assertEquals(3000L, ExitReasons.cpuMsFromStat(line, 100))
        assertNull(ExitReasons.cpuMsFromStat("garbage", 100))
    }
}
