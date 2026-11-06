package com.therapylocal.app.models

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/** The app's one [BackendGuard], kept in its own SharedPreferences file and written with `commit()` so a note survives the kill that follows it. */
object Backends {
    private const val TAG = "Backend"

    @Volatile
    private var instance: BackendGuard? = null

    fun guard(context: Context): BackendGuard =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    /**
     * A [BackendLadder] that logs under [tag] with `engine=[engine]` on every line, and writes the memory and thermal picture on every attempt.
     * A guard that cannot be opened leaves the ladder working without history.
     */
    fun ladder(context: Context, tag: String, engine: String): BackendLadder {
        val app = context.applicationContext
        val g = runCatching { guard(app) }.onFailure { AppLog.e(TAG, "backend_guard_unavailable", LogCtx.NONE, it) }.getOrNull()
        return BackendLadder(
            g,
            log = { level, evt, f, t ->
                val fields = arrayOf<Pair<String, Any?>>("engine" to engine) + f
                when (level) {
                    BackendLadder.Level.DEBUG -> AppLog.d(tag, evt, LogCtx.NONE, *fields)
                    BackendLadder.Level.INFO -> AppLog.i(tag, evt, LogCtx.NONE, *fields)
                    BackendLadder.Level.WARN -> if (t == null) AppLog.w(tag, evt, LogCtx.NONE, *fields) else AppLog.e(tag, evt, LogCtx.NONE, t, *fields)
                    BackendLadder.Level.ERROR -> AppLog.e(tag, evt, LogCtx.NONE, t, *fields)
                }
            },
            memory = { com.therapylocal.app.memory.DeviceMemory.memorySnapshot(app) + thermal(app) },
        )
    }

    /** Thermal state and battery saver: a hot or throttled phone loads and fails differently, and that only shows in the field. */
    fun thermal(context: Context): Array<Pair<String, Any?>> {
        val pm = context.getSystemService(PowerManager::class.java) ?: return emptyArray()
        return arrayOf(
            "thermal_status" to runCatching { pm.currentThermalStatus }.getOrNull(),
            "thermal_headroom_10s" to runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() },
            "power_save" to runCatching { pm.isPowerSaveMode }.getOrNull(),
        )
    }

    /** Every model's state on this phone, for the startup log and the settings screen. */
    fun overview(context: Context): List<BackendGuard.ModelView> = runCatching { guard(context).overview() }.getOrDefault(emptyList())

    /** "Test again": forgets every record and block. */
    fun reset(context: Context) {
        runCatching { guard(context).reset() }
        AppLog.w(TAG, "backend_guard_reset_by_user", LogCtx.NONE)
    }

    /** From [com.therapylocal.app.App.onCreate], before any model loads. Never throws: a broken guard must not stop the app. */
    fun recoverAtStartup(context: Context) {
        runCatching { logAcceleratorProfile(context) }.onFailure { AppLog.e(TAG, "accelerator_profile_failed", LogCtx.NONE, it) }
        runCatching {
            val g = guard(context)
            val exit = lastExit(context)
            AppLog.i(TAG, "last_exit", LogCtx.NONE, "kind" to exit?.kind?.name?.lowercase(), "foreground" to exit?.foreground, "description" to exit?.description)
            g.recover(exit).forEach { log(it, warn = true) }
            g.all().forEach { log(it, warn = false) }
            overview(context).forEach { m ->
                AppLog.i(
                    TAG, "backend_model_summary", LogCtx.NONE, "model" to m.model, "selected" to m.selected, "selected_at" to m.selectedAt,
                    "backends" to m.backends.entries.joinToString(";") { (b, v) -> "$b=${v.first?.state?.name?.lowercase() ?: "skipped"}/${v.first?.strikes ?: 0}${v.second?.let { s -> "/skip:${s.reason}" } ?: ""}" },
                )
            }
        }.onFailure { AppLog.e(TAG, "backend_guard_recover_failed", LogCtx.NONE, it) }
    }

    /**
     * What this phone offers the models, once per start: SoC and board, RAM class, GPU API levels, the NPU runtime the app would need, and the
     * system's own view of the vendor libraries. Read with the `backend_*` lines it says why a backend was or was not used.
     */
    private fun logAcceleratorProfile(context: Context) {
        val pm = context.packageManager
        val am = context.getSystemService(ActivityManager::class.java)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        AppLog.i(
            TAG, "accelerator_profile", LogCtx.NONE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE, "board" to Build.BOARD, "hardware" to Build.HARDWARE,
            "soc_manufacturer" to Build.SOC_MANUFACTURER, "soc_model" to Build.SOC_MODEL, "fingerprint" to Build.FINGERPRINT,
            "android_sdk" to Build.VERSION.SDK_INT, "security_patch" to Build.VERSION.SECURITY_PATCH, "kernel" to System.getProperty("os.version"),
            "abis" to Build.SUPPORTED_ABIS.joinToString(","),
            "low_ram_device" to am?.isLowRamDevice, "memory_class_mb" to am?.memoryClass, "large_memory_class_mb" to am?.largeMemoryClass,
            "gles_version" to am?.deviceConfigurationInfo?.glEsVersion,
            "vulkan_level" to pm.systemAvailableFeatures.firstOrNull { it.name == android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version,
            "vulkan_version" to pm.systemAvailableFeatures.firstOrNull { it.name == android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version?.let { v -> "${v shr 22}.${(v shr 12) and 0x3ff}.${v and 0xfff}" },
            "npu_dispatch_lib" to NpuRuntime.available(context), "native_lib_dir" to nativeDir,
            "native_libs" to java.io.File(nativeDir).list()?.sorted()?.joinToString(","),
            "low_memory_kill_reports" to ActivityManager.isLowMemoryKillReportSupported(),
            "build" to buildKey(context), *thermal(context),
        )
    }

    fun log(e: BackendGuard.Event, warn: Boolean) {
        val f = arrayOf<Pair<String, Any?>>(
            "model" to e.model, "backend" to e.backend, "state" to e.record?.state?.name?.lowercase(), "strikes" to e.record?.strikes,
            "ever_proven" to e.record?.everProven, "at" to e.record?.at, "reason" to e.record?.reason,
        )
        if (warn) AppLog.w(TAG, e.evt, LogCtx.NONE, *f) else AppLog.i(TAG, e.evt, LogCtx.NONE, *f)
    }

    private fun create(context: Context): BackendGuard {
        val prefs = context.getSharedPreferences("backend_guard", Context.MODE_PRIVATE)
        val store = object : BackendGuard.Store {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                val ed = prefs.edit()
                if (value == null) ed.remove(key) else ed.putString(key, value)
                ed.commit() // synchronous on purpose: the next step may be the kill this note is about
            }
            override fun keys(): Set<String> = prefs.all.keys
        }
        return BackendGuard(store, buildKey(context))
    }

    /** Changes with every install of the app and every system update (a new GPU driver comes with one). */
    private fun buildKey(context: Context): String {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "${BuildConfig.VERSION_NAME}/$installed/${Build.FINGERPRINT}"
    }

    /** The newest exit decides the strikes; the last few are all logged, since a pattern (killed every time it loads) only shows in a row. */
    private fun lastExit(context: Context): BackendGuard.LastExit? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }
            .onFailure { AppLog.w(TAG, "exit_reasons_unavailable", LogCtx.NONE, "reason" to it.message) }.getOrNull().orEmpty()
        exits.forEachIndexed { i, x ->
            AppLog.i(
                TAG, "process_exit", LogCtx.NONE, "index" to i, "at" to x.timestamp, "reason" to x.reason, "reason_name" to reasonName(x.reason), "status" to x.status,
                "importance" to x.importance, "pss_mb" to x.pss / 1024, "rss_mb" to x.rss / 1024, "process" to x.processName, "pid" to x.pid, "description" to x.description,
            )
        }
        val info = exits.firstOrNull() ?: return null
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> BackendGuard.ExitKind.KILLED_FOR_MEMORY
            // Without low-memory-kill reports the low-memory killer shows up as a plain SIGKILL.
            ApplicationExitInfo.REASON_SIGNALED ->
                if (info.status == 9 && !ActivityManager.isLowMemoryKillReportSupported()) BackendGuard.ExitKind.KILLED_FOR_MEMORY else BackendGuard.ExitKind.UNKNOWN
            ApplicationExitInfo.REASON_CRASH_NATIVE -> BackendGuard.ExitKind.NATIVE_CRASH
            ApplicationExitInfo.REASON_CRASH -> BackendGuard.ExitKind.CRASH
            ApplicationExitInfo.REASON_ANR -> BackendGuard.ExitKind.ANR
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            -> BackendGuard.ExitKind.BENIGN
            else -> BackendGuard.ExitKind.UNKNOWN
        }
        val foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        return BackendGuard.LastExit(kind, foreground, "${reasonName(info.reason)} status=${info.status} ${info.description ?: ""}".trim())
    }

    private fun reasonName(reason: Int) = when (reason) {
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
}
