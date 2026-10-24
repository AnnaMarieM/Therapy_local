package com.therapylocal.app.memory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * Runs the memory queue as a foreground service, so processing continues when the app is in the background or the screen is off.
 * The notification shows overall progress and the current stage, and carries a Stop action. The service ends itself when the queue finishes.
 */
class MemoryService : Service() {
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MemoryQueue.cancelRequested = true
            notify(build("Stopping after the current step"))
            return START_NOT_STICKY
        }
        val debugJob = intent?.getBooleanExtra(EXTRA_DEBUG_JOB, false) == true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Memory processing", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive == true) return START_NOT_STICKY

        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "therapylocal:memory").apply { acquire(6 * 60 * 60 * 1000L) }
        val app = applicationContext
        worker = Thread({
            val watcher = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        notify(build(summary()))
                        Thread.sleep(2000)
                    }
                } catch (_: InterruptedException) {
                }
            }, "memory-notif").also { it.isDaemon = true; it.start() }
            try {
                do {
                    MemoryLab(app, debugJob).run()
                } while (MemoryQueue.hasQueued() && !MemoryQueue.cancelRequested) // a session enqueued just as the run ended
            } catch (t: Throwable) {
                AppLog.e(MemoryLlm.TAG, "memory_lab_failed", LogCtx.NONE, t)
                java.io.File(app.getExternalFilesDir(null), "memory/out/ERROR").apply { parentFile?.mkdirs() }.writeText(t.stackTraceToString())
            } finally {
                watcher.interrupt()
                notify(build(summary(), ongoing = false))
                wakeLock?.takeIf { it.isHeld }?.release()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }, "memory-lab").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun summary(): String {
        val s = MemoryQueue.state.value
        val done = s.jobs.count { it.status == JobStatus.Done }
        val cur = s.jobs.firstOrNull { it.status == JobStatus.Running }
        val head = if (s.running || cur != null) "$done/${s.jobs.size} sessions" else "${s.message}: $done/${s.jobs.size} sessions"
        return if (cur != null) "$head · ${cur.client} ${cur.id.substringAfter('_')}: ${cur.stage}" else head
    }

    private fun build(text: String, ongoing: Boolean = true): Notification {
        val s = MemoryQueue.state.value
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Building client memory")
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (s.jobs.isNotEmpty()) b.setProgress(s.jobs.size, s.jobs.count { it.status == JobStatus.Done }, false)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 0, Intent(this, MemoryService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return b.build()
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    companion object {
        private const val CHANNEL = "memory"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.therapylocal.app.memory.STOP"
        private const val EXTRA_DEBUG_JOB = "debug_job"

        /** [debugJob] makes the run honour files/memory/job.json (fixed session list, settings, optional reset). The app itself never does. */
        fun start(context: Context, debugJob: Boolean = false) {
            context.startForegroundService(Intent(context, MemoryService::class.java).putExtra(EXTRA_DEBUG_JOB, debugJob))
        }
    }
}
