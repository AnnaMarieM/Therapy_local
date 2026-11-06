package com.therapylocal.app.engine.stress

import java.io.File

/** Per-process numbers from `/proc` (same uid, debuggable): cheap, unlike `dumpsys meminfo` per process per test (catalogue 4.1, finding 30). */
object ProcStats {
    /** PSS in MB from `/proc/<pid>/smaps_rollup`, or null if the process is gone or unreadable. */
    fun pssMb(pid: Int): Long? = runCatching {
        File("/proc/$pid/smaps_rollup").readLines().first { it.startsWith("Pss:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrNull()

    fun rssMb(pid: Int): Long? = runCatching {
        File("/proc/$pid/smaps_rollup").readLines().first { it.startsWith("Rss:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrNull()

    fun oomScoreAdj(pid: Int): Int? = runCatching { File("/proc/$pid/oom_score_adj").readText().trim().toInt() }.getOrNull()

    fun fdCount(pid: Int): Int? = File("/proc/$pid/fd").list()?.size

    fun alive(pid: Int): Boolean = File("/proc/$pid").exists()

    /** Pids of the app's processes by name (`<pkg>`, `<pkg>:live`, `<pkg>:batch`), from `/proc/<pid>/cmdline`. */
    fun pidsOf(processName: String): List<Int> =
        File("/proc").listFiles()?.mapNotNull { d ->
            val pid = d.name.toIntOrNull() ?: return@mapNotNull null
            val cmd = runCatching { File(d, "cmdline").readText().substringBefore('\u0000') }.getOrNull()
            pid.takeIf { cmd == processName }
        }.orEmpty()

    fun availMb(): Long = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(0)
}
