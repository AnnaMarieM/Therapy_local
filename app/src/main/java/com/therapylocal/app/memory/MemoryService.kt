package com.therapylocal.app.memory

import android.app.ActivityManager
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
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.jobs.JobRunner
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.jobs.Pipeline
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.vault.Vault

/**
 * Drains the job queue ([JobRunner]) as a foreground service, so processing continues when the app is in the background or the screen is off.
 * The notification says which step is running (session n of N, step n of N; no names) and carries a Stop action; on the lock screen only a
 * generic "Processing sessions" is shown. When the queue finishes the ongoing notification is removed; a short dismissible result is posted
 * only when the app is not on screen (success, which times out) or when something needs attention (failure or stop).
 */
class MemoryService : Service() {
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Sessions that had work in the current run, so sessions finished in earlier runs are not counted. */
    private val runSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Jobs.stopRunning()
            if (worker?.isAlive == true) notify(NOTIF_ID, buildRunning())
            return START_NOT_STICKY
        }
        val debugJob = intent?.getBooleanExtra(EXTRA_DEBUG_JOB, false) == true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Session processing", NotificationManager.IMPORTANCE_LOW))
        // Every startForegroundService needs a startForeground; while a run is going that shows the current step, not a fresh "Starting".
        if (worker?.isAlive != true) runSessions.clear()
        trackRun()
        startForeground(NOTIF_ID, buildRunning(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive == true) return START_NOT_STICKY

        nm.cancel(RESULT_ID) // a result from the previous run is outdated now
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "therapylocal:memory").apply { acquire(6 * 60 * 60 * 1000L) }
        val app = applicationContext
        worker = Thread({
            val watcher = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        trackRun()
                        notify(NOTIF_ID, buildRunning())
                        Thread.sleep(2000)
                    }
                } catch (_: InterruptedException) {
                }
            }, "memory-notif").also { it.isDaemon = true; it.start() }
            try {
                val queue = Jobs.queue(app)
                queue.clearStop()
                if (debugJob) DevHarness.seed(app)
                trackRun()
                val pipeline = Pipeline(MindStore.get(app), queue, { MemoryFiles.prompt(app, it) }, { PendingAudio.indices(app, it) }, { r, i -> PendingAudio.load(app, r, i) }, { PendingAudio.delete(app, it) })
                val runner = JobRunner(queue, pipeline.handlers, DeviceModels.pool(app)) { !JobRunner.recordingActive && ModelSetup.isReady(app) }
                do {
                    runner.drain()
                    trackRun()
                } while (runner.hasWork()) // a session enqueued just as the run ended
                if (debugJob) DevHarness.markDone(app)
            } catch (t: Throwable) {
                AppLog.e(MemoryLlm.TAG, "memory_lab_failed", LogCtx.NONE, t)
                // Stack traces can quote patient text (e.g. JSON parse errors), so they are only written for the developer harness, never in app mode.
                if (debugJob) java.io.File(MemoryFiles.devRoot(app), "out/ERROR").apply { parentFile?.mkdirs() }.writeText(t.stackTraceToString())
            } finally {
                // Join the watcher first, or its last tick can re-post an ongoing "processing" notification after the run has ended.
                watcher.interrupt()
                try { watcher.join(3000) } catch (_: InterruptedException) {}
                trackRun()
                stopForeground(STOP_FOREGROUND_REMOVE)
                postResult()
                wakeLock?.takeIf { it.isHeld }?.release()
                stopSelf()
            }
        }, "memory-lab").also { running = it; it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Adds every session that still has work to this run; finished ones were added while they were waiting. */
    private fun trackRun() {
        Jobs.snapshot.value.sessions.forEach { if (!it.finished) runSessions += it.sessionId }
    }

    private fun postResult() {
        val notice = ProcessingNotice.finished(Jobs.snapshot.value, runSessions.toSet()) ?: return
        val success = notice.title == ProcessingNotice.TITLE_DONE
        // On success the app on screen already shows the result; a notification is only useful when the user is elsewhere.
        if (success && isAppInForeground()) return
        val b = base(notice.title, notice.text)
            .setAutoCancel(true)
            .setCategory(if (success) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
        if (success) b.setTimeoutAfter(RESULT_TIMEOUT_MS)
        notify(RESULT_ID, b.setPublicVersion(publicVersion(notice.title)).build())
    }

    private fun buildRunning(): Notification {
        val s = Jobs.snapshot.value
        val n = ProcessingNotice.running(s, runSessions.toSet())
        val b = base(n.title, n.text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPublicVersion(publicVersion("Processing sessions"))
        if (n.progressMax > 0) b.setProgress(n.progressMax, n.progress, n.indeterminate)
        if (!s.stopping) {
            val stop = PendingIntent.getService(this, 0, Intent(this, MemoryService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return b.build()
    }

    private fun base(title: String, text: String): Notification.Builder {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return b
    }

    /** Lock-screen version: no names, no session details. */
    private fun publicVersion(title: String): Notification =
        Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setShowWhen(false).build()

    private fun isAppInForeground(): Boolean =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    private fun notify(id: Int, n: Notification) = getSystemService(NotificationManager::class.java).notify(id, n)

    companion object {
        private const val CHANNEL = "memory"
        private const val NOTIF_ID = 42
        private const val RESULT_ID = 43
        private const val RESULT_TIMEOUT_MS = 10_000L
        const val ACTION_STOP = "com.therapylocal.app.memory.STOP"
        private const val EXTRA_DEBUG_JOB = "debug_job"

        /** [debugJob] makes the run honour files/memory/job.json (fixed session list, settings, optional reset). The app itself never does. */
        fun start(context: Context, debugJob: Boolean = false) {
            context.startForegroundService(Intent(context, MemoryService::class.java).putExtra(EXTRA_DEBUG_JOB, debugJob))
        }

        /**
         * After an unlock, a finished download or a recording: continues a queue that still has pending jobs (the process was killed, the app
         * locked, or the runner paused). The runner needs the vault key and the AI files; until both are there the jobs just wait in the queue.
         */
        fun resumePending(context: Context) {
            if (Vault.isUnlocked && ModelSetup.isReady(context) && Jobs.queue(context).hasPending()) start(context.applicationContext)
        }

        /** The worker of the current or last run, for [awaitStopped]. */
        @Volatile
        private var running: Thread? = null

        /** Asks a running queue to stop after its current step and stops the service. */
        fun stop(context: Context) {
            Jobs.stopRunning()
            context.stopService(Intent(context, MemoryService::class.java))
        }

        /** Waits up to [timeoutMs] for the worker to finish its step after [stop]. Not on the main thread. */
        fun awaitStopped(timeoutMs: Long) {
            try {
                running?.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
