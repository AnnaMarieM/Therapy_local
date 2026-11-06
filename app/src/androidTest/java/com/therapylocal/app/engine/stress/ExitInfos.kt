package com.therapylocal.app.engine.stress

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.engine.ExitReasons
import com.therapylocal.app.models.BackendGuard.ExitKind

/**
 * `getHistoricalProcessExitReasons`, polled and filtered the way the catalogue needs it (L3, U0, the `exits` section of the data bundle).
 * AMS writes the record asynchronously, so every wait is a bounded poll (anti-flake rule 4a: one of the three places a retry is allowed).
 *
 *     val t0 = ExitInfos.watermark()                      // wall clock now
 *     ... a host dies ...
 *     val got = ExitInfos.awaitNew("${pkg}:batch", t0)     // List<ApplicationExitInfo>, newest first
 *     assertEquals(ExitKind.NATIVE_CRASH, ExitInfos.kindOf(got.first()))
 *     ExitInfos.assertMainIntact(t0)                       // U0: no CRASH / CRASH_NATIVE / ANR of main since t0
 */
object ExitInfos {
    class Found(val info: ApplicationExitInfo, val latencyMs: Long)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val am get() = ctx.getSystemService(ActivityManager::class.java)

    /** The package's main process name, and the host process names. */
    val main: String get() = ctx.packageName
    val live: String get() = "${ctx.packageName}:live"
    val batch: String get() = "${ctx.packageName}:batch"

    /** The first record for [pid], polled up to [timeoutMs]; null when none arrived (a failed assertion in the caller). */
    fun await(pid: Int, timeoutMs: Long = 5_000): Found? {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            am.getHistoricalProcessExitReasons(ctx.packageName, pid, 1).firstOrNull()?.let { return Found(it, SystemClock.elapsedRealtime() - t0) }
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return null
            Thread.sleep(50) // bounded poll, see above
        }
    }

    /** The point in time (wall clock, ms) that later calls compare record timestamps with. Taken at `@Before`. */
    fun watermark(): Long = System.currentTimeMillis()

    /** Every stored record of the package (all processes), newest first. */
    fun all(max: Int = 64): List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(ctx.packageName, 0, max)

    /**
     * Records written at or after [sinceWallMs], newest first. [processName] filters by process (`ExitInfos.main`, `.live`, `.batch`);
     * `null` is all of them.
     */
    fun since(sinceWallMs: Long, processName: String? = null): List<ApplicationExitInfo> =
        all().filter { it.timestamp >= sinceWallMs && (processName == null || it.processName == processName) }

    /**
     * Waits (bounded) until at least [count] records of [processName] exist since [sinceWallMs]; returns what is there when the bound is hit,
     * so the caller can assert with a message. Newest first.
     */
    fun awaitNew(processName: String, sinceWallMs: Long, count: Int = 1, timeoutMs: Long = 5_000): List<ApplicationExitInfo> {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val r = since(sinceWallMs, processName)
            if (r.size >= count || SystemClock.elapsedRealtime() - t0 > timeoutMs) return r
            Thread.sleep(50) // bounded poll, see above
        }
    }

    /** How the app maps this record. Uses the lane's `lmk_reports` fact (P-01) unless [lowMemoryKillReports] is given. */
    fun mapped(info: ApplicationExitInfo, lowMemoryKillReports: Boolean = lmkReports()): ExitReasons.Mapped =
        ExitReasons.map(ExitReasons.of(info), lowMemoryKillReports)

    fun kindOf(info: ApplicationExitInfo, lowMemoryKillReports: Boolean = lmkReports()): ExitKind = mapped(info, lowMemoryKillReports).kind

    /** `isLowMemoryKillReportSupported` as the probes recorded it. */
    fun lmkReports(): Boolean {
        LaneCaps.ensure()
        return LaneCaps.bool("lmk_reports")
    }

    /** `reason=crash_native status=11 ...`, for assertion messages. */
    fun describe(info: ApplicationExitInfo): String =
        "${info.processName} pid=${info.pid} ${ExitReasons.describe(ExitReasons.of(info))} importance=${info.importance}"

    /** Adds [info] to the data bundle's `exits` array, with the kind the app derives and the kind the test expected. */
    fun record(into: RunRecord, info: ApplicationExitInfo, expected: ExitKind? = null) {
        into.exit(info.processName, info.pid, ExitReasons.reasonName(info.reason), info.status, kindOf(info).name, info.description, expected?.name)
    }

    private val BAD_FOR_MAIN = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

    /** Records of the main process since [sinceWallMs] that a user would have seen (crash, native crash, ANR). */
    fun badForMain(sinceWallMs: Long): List<ApplicationExitInfo> = since(sinceWallMs, main).filter { it.reason in BAD_FOR_MAIN }

    /** U0, the exit-record part: fails with the records when main crashed or hung since [sinceWallMs]. */
    fun assertMainIntact(sinceWallMs: Long) {
        val bad = badForMain(sinceWallMs)
        if (bad.isNotEmpty()) throw AssertionError("main process ended badly: " + bad.joinToString("; ") { describe(it) })
    }
}
