package com.therapylocal.app.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.therapylocal.app.models.BackendGuard.ExitKind

/** One `ApplicationExitInfo`, as plain data (the platform class cannot be built in JVM tests). */
data class ExitRecord(
    val reason: Int, val status: Int, val timestamp: Long, val processName: String, val pid: Int,
    val importance: Int, val pssKb: Long, val rssKb: Long, val description: String?,
)

/**
 * Exit records and what they mean. One mapping, shared by the host connections and `Backends.lastExit`, so a halt code, a freezer kill or
 * a dependency death never fall into the default branch by accident.
 */
object ExitReasons {
    /** `getHistoricalProcessExitReasons(pkg, pid, max)`. */
    fun interface Source { fun query(pid: Int): List<ExitRecord> }

    /** A mapped record. [why] is set for a benign end we or the host chose (`HostExit.why`, `freezer`). */
    data class Mapped(val kind: ExitKind, val why: String?, val reasonName: String)

    fun of(info: ApplicationExitInfo) = ExitRecord(
        info.reason, info.status, info.timestamp, info.processName, info.pid, info.importance, info.pss, info.rss, info.description,
    )

    fun source(context: Context): Source = Source { pid ->
        val am = context.getSystemService(ActivityManager::class.java) ?: return@Source emptyList()
        runCatching { am.getHistoricalProcessExitReasons(context.packageName, pid, 1).map(::of) }.getOrDefault(emptyList())
    }

    /**
     * The record of this host process: same pid, `processName == "<pkg>:<role>"`, and written at or after the generation's bind time (wall
     * clock), so an older record of a recycled pid is never used. The newest qualifying record wins.
     */
    fun pick(records: List<ExitRecord>, pid: Int, processName: String, sinceWallMs: Long): ExitRecord? =
        records.filter { it.pid == pid && it.processName == processName && it.timestamp >= sinceWallMs }.maxByOrNull { it.timestamp }

    /** The newest record of the process [processName] written at or after [sinceWallMs]: how a host that was running at the last start ended. */
    fun newest(records: List<ExitRecord>, processName: String, sinceWallMs: Long): ExitRecord? =
        records.filter { it.processName == processName && it.timestamp >= sinceWallMs }.maxByOrNull { it.timestamp }

    fun map(r: ExitRecord, lowMemoryKillReports: Boolean): Mapped {
        val name = reasonName(r.reason)
        fun m(kind: ExitKind, why: String? = null) = Mapped(kind, why, name)
        return when (r.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> m(ExitKind.KILLED_FOR_MEMORY)
            // Without low-memory-kill reports the low-memory killer shows up as a plain SIGKILL.
            ApplicationExitInfo.REASON_SIGNALED -> m(if (r.status == 9 && !lowMemoryKillReports) ExitKind.KILLED_FOR_MEMORY else ExitKind.UNKNOWN)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> m(ExitKind.NATIVE_CRASH)
            ApplicationExitInfo.REASON_CRASH -> m(ExitKind.CRASH)
            ApplicationExitInfo.REASON_ANR -> m(ExitKind.ANR)
            // A host ends itself with halt(code) or _exit(code), never a memory kill: 10..12 on purpose, 13 after a logged Java crash, 100+N
            // after the native handler caught signal N (AD-04). The last two are struck like the crash they are.
            ApplicationExitInfo.REASON_EXIT_SELF -> when {
                HostExit.nativeSignal(r.status) != null -> m(ExitKind.NATIVE_CRASH)
                r.status == HostExit.JAVA_CRASH.code -> m(ExitKind.CRASH, HostExit.JAVA_CRASH.why)
                else -> m(ExitKind.BENIGN, HostExit.entries.firstOrNull { it.benign && it.code == r.status }?.why)
            }
            ApplicationExitInfo.REASON_FREEZER -> m(ExitKind.BENIGN, "freezer")
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> m(ExitKind.UNKNOWN)
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            -> m(ExitKind.BENIGN)
            else -> m(ExitKind.UNKNOWN)
        }
    }

    fun describe(r: ExitRecord): String {
        if (r.reason == ApplicationExitInfo.REASON_EXIT_SELF) {
            HostExit.nativeSignal(r.status)?.let { return "signal $it" }
            if (r.status == HostExit.JAVA_CRASH.code) return HostExit.JAVA_CRASH.why
        }
        return "${reasonName(r.reason)} status=${r.status} ${r.description ?: ""}".trim()
    }

    fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown_$reason"
    }

    /** `utime + stime` of a `/proc/<pid>/stat` line in ms (clock ticks of [ticksPerSecond]); null if the line is not understood. */
    fun cpuMsFromStat(line: String, ticksPerSecond: Long = 100): Long? {
        // The comm field (2) is in parentheses and may hold spaces: split after the last ')'. Fields 14 and 15 are utime and stime.
        val rest = line.substringAfterLast(')', "").trim().split(' ')
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return (utime + stime) * 1000 / ticksPerSecond
    }
}
