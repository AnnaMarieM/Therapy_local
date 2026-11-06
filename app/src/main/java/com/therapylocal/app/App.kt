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
import com.therapylocal.app.models.SpeechMigration

class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleStore.wrap(base))

    override fun onCreate() {
        super.onCreate()
        val role = EngineProcess.current()
        AppLog.init(this, role?.proc ?: "main")
        // An engine process holds patient text: its crash lines carry types and frames only.
        AppLog.installCrashHandler(redacted = role != null)
        if (role != null) {
            // A host needs no SQLCipher, device facts, startup recovery, display config, speech migration or vault: main owns them.
            AppLog.logMemory(this, tag = "Memory", evt = "app_start")
            return
        }
        System.loadLibrary("sqlcipher") // before anything opens a database
        // Logging only: a device that answers oddly must not stop the app from starting.
        runCatching { DeviceFacts.logStartup(this) }.onFailure { AppLog.e("Device", "startup_facts_failed", LogCtx.NONE, it) }
        // Once per install: removes the old speech-engine settings and recogniser files (Canary is the only recogniser now).
        SpeechMigration.run(this)
        // Before any model loads: turns a load the last process died in into a strike, so a backend that kills this phone is not retried forever.
        Backends.recoverAtStartup(this)
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
