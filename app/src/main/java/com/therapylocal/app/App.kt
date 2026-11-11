package com.therapylocal.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.therapylocal.app.config.DisplayConfig
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineProcess
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.DeviceFacts
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Backends

class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.therapylocal.app.ui.design.DisplayPin.wrap(LocaleStore.wrap(base)))

    override fun onCreate() {
        super.onCreate()
        val role = EngineProcess.current()
        AppLog.init(this, role?.proc ?: "main")
        if (role != null) com.therapylocal.app.engine.Faults.hostStart(this, role)
        // An engine process holds patient text: its crash lines carry types and frames only.
        // and ends itself quietly (halt) after logging: a crash reported to the system would show "keeps stopping" for the whole app (AD-04).
        AppLog.installCrashHandler(redacted = role != null, haltCode = if (role != null) com.therapylocal.app.engine.HostExit.JAVA_CRASH.code else null)
        if (role != null) {
            // Fatal native signals end the host with _exit(100 + signal) instead of a tombstone and the system crash dialog (AD-04).
            com.therapylocal.app.engine.NativeQuietCrash.installLogged()
            // A host needs no SQLCipher, device facts, startup recovery, display config or vault: main owns them.
            AppLog.logMemory(this, tag = "Memory", evt = "app_start")
            return
        }
        System.loadLibrary("sqlcipher") // before anything opens a database
        // Logging only: a device that answers oddly must not stop the app from starting.
        runCatching { DeviceFacts.logStartup(this) }.onFailure { AppLog.e("Device", "startup_facts_failed", LogCtx.NONE, it) }
        // Before any model loads: turns a load the last process died in into a strike, so a backend that kills this phone is not retried forever.
        Backends.recoverAtStartup(this)
        // Names and sizes only; nothing is deleted (AD-03). Off the main thread: it walks the models folder. (DeviceFacts' model_files walks it inline.)
        val app = this
        Thread({ com.therapylocal.app.models.ModelsInventory.log(app) }, "models-inventory").apply { isDaemon = true }.start()
        DisplayConfig.init(this)
        AppLog.logMemory(this, tag = "Memory", evt = "app_start")
    }

    override fun onTrimMemory(level: Int) {
        // Do NOT call super.onTrimMemory() if registering component callbacks, or simply do not registerComponentCallbacks(this)
        // since Application already receives onTrimMemory from the OS.
        super.onTrimMemory(level)
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }
        AppLog.w("Memory", "system_trim_memory", LogCtx.NONE, "level" to levelName, "level_code" to level)
        AppLog.logMemory(this, tag = "Memory", evt = "trim_snapshot", extra = arrayOf("trim_level" to levelName))
        // In a host only log: unloading there would leave main believing in a handle the host dropped. lmkd or main's idle logic decides.
        if (!EngineProcess.isMain) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // UI_HIDDEN and BACKGROUND (the only levels from API 34) mean the app left the screen, not that memory is short.
            Engines.onLowMemory(critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.e("Memory", "system_on_low_memory", LogCtx.NONE)
        AppLog.logMemory(this, tag = "Memory", evt = "low_memory_snapshot")
        if (EngineProcess.isMain) Engines.onLowMemory(critical = true)
    }
}
