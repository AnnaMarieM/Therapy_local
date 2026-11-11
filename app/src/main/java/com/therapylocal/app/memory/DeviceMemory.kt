package com.therapylocal.app.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Memory telemetry for the log lines. It decides nothing: whether a GPU/NPU load is tried never depends on free memory (AD-08), and whether a phone
 * can really run it is what [com.therapylocal.app.models.BackendGuard] learns by trying.
 * Nothing here looks at the device's name, its SoC or its total RAM to choose a backend.
 */
object DeviceMemory {
    private const val MB = 1024L * 1024L

    /** Gathers detailed, high-resolution memory telemetry from the OS and process runtime. */
    fun memorySnapshot(context: Context): Array<Pair<String, Any?>> {
        val am = context.getSystemService(ActivityManager::class.java)
        val sysMem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dbgMem = Debug.MemoryInfo().also { runCatching { Debug.getMemoryInfo(it) } }

        val pssTotal = dbgMem.totalPss.toLong() / 1024L
        val glMtrack = dbgMem.getMemoryStat("summary.graphics")?.toLongOrNull()?.let { it / 1024L } ?: -1L
        val javaHeap = dbgMem.getMemoryStat("summary.java-heap")?.toLongOrNull()?.let { it / 1024L } ?: -1L
        val nativeHeap = dbgMem.getMemoryStat("summary.native-heap")?.toLongOrNull()?.let { it / 1024L } ?: -1L
        val totalRss = dbgMem.getMemoryStat("summary.total-rss")?.toLongOrNull()?.let { it / 1024L } ?: -1L

        return arrayOf(
            "sys_total_mb" to (sysMem.totalMem / MB),
            "sys_avail_mb" to (sysMem.availMem / MB),
            "sys_low_mem" to sysMem.lowMemory,
            "sys_thresh_mb" to (sysMem.threshold / MB),
            "proc_pss_mb" to pssTotal,
            "proc_rss_mb" to totalRss,
            "gl_mtrack_mb" to glMtrack,
            "java_heap_mb" to javaHeap,
            "native_heap_mb" to nativeHeap,
        )
    }
}

