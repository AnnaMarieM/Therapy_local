package com.therapylocal.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process, and so the microphone, alive while a session records with the app in the background or the screen off
 * (foreground service of type microphone). The notification shows the running time and never a client name; tapping it opens the session.
 * [SessionController] starts and stops it; it has no logic of its own.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, build(SessionController.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            // Android refuses a microphone service started from the background; the recording then only lasts while the app is open.
            AppLog.e(TAG, "recording_service_refused", LogCtx.NONE, t)
            stopSelf()
            return START_NOT_STICKY
        }
        watch?.cancel()
        watch = scope.launch {
            SessionController.state.map { it.phase to it.clock }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(SessionController.state.value))
            }
        }
        // The process dying ends the recording with it; there is nothing to restart into.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(s: SessionState): Notification {
        val paused = s.phase == SessionPhase.Paused
        val title = if (paused) "Session paused" else "Recording session"
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(if (paused) "Tap to continue" else "Audio stays on this phone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (!paused && s.active) {
            // The chronometer counts from "now minus elapsed", so it matches the screen without the service ticking.
            val elapsed = s.clock.elapsedMs(SystemClock.elapsedRealtime())
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsed)
        } else {
            b.setShowWhen(false)
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.putExtra(EXTRA_OPEN_SESSION, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            b.setContentIntent(PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return b.build()
    }

    companion object {
        private const val TAG = "Session"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 44

        /** Set on the launch intent from the notification: open the running session. */
        const val EXTRA_OPEN_SESSION = "open_session"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java))
            } catch (t: Throwable) {
                AppLog.e(TAG, "recording_service_start_failed", LogCtx.NONE, t)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
