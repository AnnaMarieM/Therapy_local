package com.therapylocal.app.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * Universal hardware profiling and memory tiering for devices with 6 GB+ RAM.
 *
 * Prevents Linux Low Memory Killer (lmkd) kills by ensuring models that allocate massive
 * unswappable OpenCL buffers on unified GPU memory (such as ARM Mali on Google Tensor / Samsung Exynos)
 * run safely on CPU unless the device has expansive RAM (>= 10 GB, e.g. Snapdragon 8 Elite).
 */
object DeviceMemory {
    private const val TAG = "Memory"
    private const val MB = 1024L * 1024L

    enum class Tier {
        EXPANSIVE,  // >= 10 GB RAM (e.g. Poco Snapdragon 8 Elite, 12 GB+)
        BALANCED,   // 6 GB - 8 GB RAM (e.g. Pixel 8 Tensor G3)
        CONSTRAINED // < 6 GB RAM
    }

    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mem.totalMem / MB
    }

    fun availableRamMb(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mem.availMem / MB
    }

    fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mem.lowMemory
    }

    fun tier(context: Context): Tier {
        val total = totalRamMb(context)
        return when {
            total >= 9_500 -> Tier.EXPANSIVE
            total >= 5_500 -> Tier.BALANCED
            else -> Tier.CONSTRAINED
        }
    }

    /**
     * True if the SoC uses an ARM Mali or Samsung GPU where OpenCL allocations for large graphs
     * (like EmbeddingGemma's 1,031 kernels) inflate GL mtrack by 4+ GB of unswappable system RAM.
     */
    fun isMaliOrTensor(): Boolean {
        val mfg = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.lowercase() else ""
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.lowercase() else ""
        val hw = Build.HARDWARE.lowercase()
        return mfg.contains("google") || mfg.contains("samsung") ||
                model.contains("tensor") || model.contains("exynos") ||
                hw.contains("zuma") || hw.contains("shiba") || hw.contains("husky") ||
                hw.contains("akita") || hw.contains("mali")
    }

    /**
     * Determines whether the live [com.therapylocal.app.embed.Embedder] can safely use the GPU.
     *
     * On devices with 6-8 GB RAM or Mali/Tensor GPUs, OpenCL causes 4+ GB graphics memory spikes,
     * immediately triggering lmkd SIGKILL. Such devices run the embedder on CPU, which takes only
     * ~150 MB RSS and runs smoothly without endangering the system.
     */
    fun allowEmbedderGpu(context: Context): Boolean {
        val t = tier(context)
        if (t != Tier.EXPANSIVE) {
            AppLog.i(TAG, "embedder_gpu_skipped", LogCtx.NONE, "reason" to "ram_tier_${t.name.lowercase()}", "total_ram_mb" to totalRamMb(context))
            return false
        }
        if (isMaliOrTensor()) {
            AppLog.i(TAG, "embedder_gpu_skipped", LogCtx.NONE, "reason" to "mali_tensor_gpu_driver", "soc" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"))
            return false
        }
        val (available, low) = LlmMemory.available(context)
        if (low || available < (3L shl 30)) {
            AppLog.w(TAG, "embedder_gpu_skipped", LogCtx.NONE, "reason" to "low_available_ram", "avail_mb" to (available / MB))
            return false
        }
        return true
    }

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
            "tier" to tier(context).name.lowercase(),
        )
    }
}

