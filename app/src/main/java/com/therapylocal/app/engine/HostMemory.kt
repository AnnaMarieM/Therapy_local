package com.therapylocal.app.engine

import android.os.Debug
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * What this engine process itself holds, logged while a long call runs (open, ask, transcribe, diarize): the exact cost of a model, as the
 * process is charged for it, not the dip in the system's free memory. It runs inside the host because another process cannot read it
 * (the main process gets a wrong figure from `getProcessMemoryInfo` and SELinux refuses `/proc/<pid>/smaps_rollup`; measured 2026-11-11).
 * Fields: `pss_mb`, `rss_mb`, `swap_pss_mb`, `gfx_mb` (graphics/GL buffers mapped into this process), `native_mb`, `java_mb`, and `peak_pss_mb`
 * (the highest PSS sampled since the process started). GPU memory the driver keeps outside the process mappings is not in any of them.
 */
object HostMemory {
    private const val INTERVAL_MS = 2_000L
    @Volatile private var peakKb = 0L

    /** Samples before, every [INTERVAL_MS] during, and after [block]. Never throws and never delays [block]. */
    fun <T> around(op: String, model: String?, backend: String?, block: () -> T): T {
        val ctx = arrayOf<Pair<String, Any?>>("op" to op, "model" to model, "backend_try" to backend)
        sample("start", ctx)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread({
            while (!stop.get()) {
                try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                if (!stop.get()) sample("during", ctx)
            }
        }, "host-mem").apply { isDaemon = true; start() }
        try { return block() } finally { stop.set(true); t.interrupt(); sample("end", ctx) }
    }

    fun sample(at: String, ctx: Array<Pair<String, Any?>>) {
        runCatching {
            val m = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            fun mb(k: String) = m.getMemoryStat(k)?.toLongOrNull()?.let { it / 1024L } ?: -1L
            val pss = m.totalPss.toLong()
            if (pss > peakKb) peakKb = pss
            AppLog.i("Engine", "host_mem", LogCtx.NONE, "at" to at, *ctx,
                "pss_mb" to pss / 1024L, "rss_mb" to mb("summary.total-rss"), "swap_pss_mb" to m.totalSwappablePss.toLong() / 1024L,
                "gfx_mb" to mb("summary.graphics"), "native_mb" to mb("summary.native-heap"), "java_mb" to mb("summary.java-heap"),
                "peak_pss_mb" to peakKb / 1024L)
        }
    }
}
