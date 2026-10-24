package com.therapylocal.app

import android.app.Application
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.DeviceFacts

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        DeviceFacts.logStartup(this)
    }
}
