package com.therapylocal.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.therapylocal.app.config.DisplayConfig
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.DeviceFacts
import com.therapylocal.app.log.LogCtx

class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleStore.wrap(base))

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.installCrashHandler()
        System.loadLibrary("sqlcipher") // before anything opens a database
        DeviceFacts.logStartup(this)
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Engines.onLowMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.e("Memory", "system_on_low_memory", LogCtx.NONE)
        AppLog.logMemory(this, tag = "Memory", evt = "low_memory_snapshot")
        Engines.onLowMemory()
    }
}
