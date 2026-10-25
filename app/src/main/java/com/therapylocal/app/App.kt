package com.therapylocal.app

import android.app.Application
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.DeviceFacts

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher") // before anything opens a database
        AppLog.init(this)
        DeviceFacts.logStartup(this)
    }
}
