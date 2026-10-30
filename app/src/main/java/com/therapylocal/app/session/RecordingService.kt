package com.therapylocal.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.ui.logic.SessionLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
            // Redrawn on every change and, while recording, every second: the status bar chip and the notification say the running time
            // as it runs, the evidence that it is really recording.
            // Only while a session is on: once it is finishing or over, the service is about to stop, and a redraw now would post a
            // "Recording 00:00" notification that outlives it.
            SessionController.state.map { it.phase to it.clock }.distinctUntilChanged().collectLatest {
                val nm = getSystemService(NotificationManager::class.java)
                while (SessionController.state.value.active) {
                    nm.notify(NOTIF_ID, build(SessionController.state.value))
                    if (SessionController.state.value.phase != SessionPhase.Recording) break
                    delay(1_000L - SessionController.state.value.clock.elapsedMs(SystemClock.elapsedRealtime()) % 1_000L)
                }
            }
        }
        // The process dying ends the recording with it; there is nothing to restart into.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // The recording is over: its notification and status bar chip go with it, whatever was posted last.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        super.onDestroy()
    }

    private fun build(s: SessionState): Notification {
        val paused = s.phase == SessionPhase.Paused
        val time = SessionLogic.formatMinSec(s.clock.elapsedMs(SystemClock.elapsedRealtime()) / 1000)
        val who = s.clientName.ifEmpty { s.client }
        // Unlocked: who, what, how long ("George M. · Recording · 04:12"). On the lock screen only that a session records: the name of a
        // client is not for whoever picks up the phone.
        val title = if (who.isEmpty()) (if (paused) "Session paused" else "Recording") else "$who · ${if (paused) "Paused" else "Recording"}"
        val publicTitle = if (paused) "Session paused" else "Recording session"
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$title · $time")
            .setContentText(if (paused) "Tap to continue" else "Audio stays on this phone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(publicTitle).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(false)
        // Android 16+: ask to be a Live Update, the chip in the status bar by the camera, so a recording is visible from every app, as
        // navigation and calls are. A Live Update is asked for with two notification extras (Notification.EXTRA_REQUEST_PROMOTED_ONGOING
        // and the chip's short text, EXTRA_SHORT_CRITICAL_TEXT: here the running time); they are set by name so the app builds against
        // SDK 34, and older Android ignores them. The system (and the person, in settings) decides; if it declines, this stays an ordinary
        // ongoing notification.
        if (Build.VERSION.SDK_INT >= 36) {
            b.addExtras(Bundle().apply {
                putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                putString(EXTRA_SHORT_CRITICAL_TEXT, if (paused) "Paused $time" else time)
            })
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
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

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
