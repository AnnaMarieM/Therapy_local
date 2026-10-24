package com.therapylocal.app.log

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.therapylocal.app.BuildConfig
import java.io.File

/** Logs device and runtime facts once at startup so every trace can be read in context. */
object DeviceFacts {
    private const val TAG = "Device"

    val socModel: String get() = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"

    fun logStartup(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ext = context.getExternalFilesDir(null)
        AppLog.i(
            TAG, "startup",
            f = arrayOf(
                "app_version" to BuildConfig.VERSION_NAME,
                "build_type" to BuildConfig.BUILD_TYPE,
                "full_text_logging" to AppLog.fullTextLogging,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "soc_manufacturer" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "unknown",
                "soc_model" to socModel,
                "android_sdk" to Build.VERSION.SDK_INT,
                "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                "ram_total_mb" to mem.totalMem / MB,
                "ram_avail_mb" to mem.availMem / MB,
                "low_memory" to mem.lowMemory,
                "storage_free_mb" to (ext?.usableSpace ?: 0L) / MB,
                "cpu_cores" to Runtime.getRuntime().availableProcessors(),
            ),
        )
        // Cheap availability hints. The real answer is whichever backend actually loads (see Engine/Embed spans).
        AppLog.i(
            TAG, "backend_hints",
            f = arrayOf(
                "opencl_lib" to anyExists("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so"),
                "vulkan_lib" to anyExists("/vendor/lib64/hw/vulkan.adreno.so", "/system/lib64/libvulkan.so"),
                "hexagon_cdsp_rpc" to anyExists("/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so"),
                "native_lib_dir" to context.applicationInfo.nativeLibraryDir,
            ),
        )
        val models = File(ext, "models")
        val files = models.walkTopDown().filter { it.isFile }.toList()
        AppLog.i(
            TAG, "model_files",
            f = arrayOf(
                "count" to files.size,
                "total_mb" to files.sumOf { it.length() } / MB,
                "files" to files.joinToString(",") { "${it.relativeTo(models).path}:${it.length()}" },
            ),
        )
    }

    private fun anyExists(vararg paths: String) = paths.any { File(it).exists() }

    private const val MB = 1024L * 1024L
}
