package com.therapylocal.app

import android.app.Application
import android.content.Context
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.DeviceFacts

class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleStore.wrap(base))

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher") // before anything opens a database
        AppLog.init(this)
        DeviceFacts.logStartup(this)
    }
}
