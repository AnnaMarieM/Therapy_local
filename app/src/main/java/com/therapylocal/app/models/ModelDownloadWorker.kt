package com.therapylocal.app.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable, checksummed download of every missing file of [ModelSetup.required] into app-private external storage,
 * under one notification. Files that are already complete are skipped, so nothing is pulled twice.
 */
class ModelDownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastNotified = 0L
    private var total = 0L
    private var lastDone = 0L

    // Smoothed speed (bytes/s): exponential moving average over ~1 s samples.
    private var startedAt = 0L
    private var sampleAt = 0L
    private var sampleDone = 0L
    private var speed = 0.0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = LogCtx.NONE
        val specs = ModelSetup.required(context)
        if (specs.all { it.isReady(context) }) {
            AppLog.i(TAG, "download_skip", ctx, "reason" to "already_complete")
            return@withContext finish()
        }

        total = specs.sumOf { it.totalBytes }
        val missing = specs.sumOf { it.missingBytes(context) }
        val free = specs.first().dir(context).usableSpace
        if (free < missing + FREE_SPACE_MARGIN) {
            val need = missing + FREE_SPACE_MARGIN - free
            AppLog.e(TAG, "setup_failed", ctx, null, "reason" to "no_space", "need_mb" to (missing shr 20), "free_mb" to (free shr 20))
            notifyPaused(need)
            return@withContext Result.failure(error("Free up ${ModelSetup.formatBytes(need)} of storage to finish setting up."))
        }

        startedAt = System.currentTimeMillis()
        sampleAt = startedAt
        sampleDone = total - missing
        lastDone = sampleDone
        setForeground(foregroundInfo(lastDone, null, false))
        try {
            AppLog.span(TAG, "model_download", ctx, "total_bytes" to total, "to_fetch_bytes" to missing) { span ->
                var doneBefore = 0L
                for (spec in specs) for (f in spec.files) {
                    if (spec.isComplete(context, f)) {
                        doneBefore += f.size
                        continue
                    }
                    downloadFile(spec, f, doneBefore)
                    report(PHASE_VERIFYING, doneBefore + f.size)
                    val sha = AppLog.span(TAG, "model_verify", ctx, "model" to spec.id, "file" to f.name, "bytes" to f.size) { sha256(spec.part(context, f)) }
                    if (sha != f.sha256) {
                        spec.part(context, f).delete()
                        AppLog.e(TAG, "checksum_mismatch", ctx, null, "model" to spec.id, "file" to f.name, "got" to sha)
                        return@span Result.retry()
                    }
                    check(spec.part(context, f).renameTo(spec.file(context, f))) { "Could not move ${f.name} into place" }
                    doneBefore += f.size
                }
                span.put("fetched_bytes", missing)
                finish()
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "download_interrupted", ctx, "err" to e.message, "will_retry" to true)
            Result.retry()
        }
    }

    /** Everything is on disk: announce it and let the processing queue pick up recordings made meanwhile. */
    private fun finish(): Result {
        AppLog.i(TAG, "setup_done", LogCtx.NONE)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Setup", NotificationManager.IMPORTANCE_LOW))
        nm.notify(
            NOTIFICATION_DONE_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MindVault is ready")
                .setContentText("Recorded sessions will now be processed automatically.")
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build(),
        )
        if (com.therapylocal.app.vault.Vault.isUnlocked) com.therapylocal.app.memory.MemoryService.resumePending(applicationContext)
        return Result.success()
    }

    private fun notifyPaused(need: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Setup", NotificationManager.IMPORTANCE_LOW))
        nm.notify(
            NOTIFICATION_DONE_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Setup paused")
                .setContentText("Free up ${ModelSetup.formatBytes(need)} of storage to finish setting up.")
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    private suspend fun downloadFile(spec: ModelSpec, f: ModelFile, doneBefore: Long) {
        val part = spec.part(context, f)
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!resumed && conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            AppLog.i(TAG, "download_file_start", LogCtx.NONE, "model" to spec.id, "file" to f.name, "bytes" to f.size, "resumed_from" to if (resumed) have else 0L)
            var done = if (resumed) have else 0L
            var lastReport = 0L
            var lastPct = -1
            FileOutputStream(part, resumed).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 500) {
                            lastReport = now
                            report(PHASE_DOWNLOADING, doneBefore + done)
                            val pct = (done * 100 / f.size).toInt()
                            if (pct / 10 != lastPct / 10) {
                                lastPct = pct
                                AppLog.d(TAG, "download_progress", LogCtx.NONE, "model" to spec.id, "file" to f.name, "pct" to pct)
                            }
                        }
                    }
                }
            }
            if (done != f.size) throw IOException("Short download: $done of ${f.size}")
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Folds a ~1 s sample into the smoothed speed and returns the seconds left, or null until ~3 s of data exist. */
    private fun eta(done: Long): Long? {
        val now = System.currentTimeMillis()
        if (now - sampleAt >= 1000) {
            val inst = (done - sampleDone) * 1000.0 / (now - sampleAt)
            speed = if (speed == 0.0) inst else speed * 0.8 + inst * 0.2
            sampleAt = now
            sampleDone = done
        }
        if (now - startedAt < 3000 || speed < 1.0) return null
        return ((total - done).coerceAtLeast(0) / speed).toLong()
    }

    private suspend fun report(phase: String, done: Long) {
        lastDone = done
        val verifying = phase == PHASE_VERIFYING
        val eta = if (verifying) null else eta(done)
        setProgress(
            Data.Builder().putString(KEY_PHASE, phase).putLong(KEY_DONE, done).putLong(KEY_TOTAL, total).putLong(KEY_ETA, eta ?: -1L).build(),
        )
        val now = System.currentTimeMillis()
        if (now - lastNotified > 1500) {
            lastNotified = now
            setForeground(foregroundInfo(done, eta, verifying))
        }
    }

    private fun error(message: String): Data = Data.Builder().putString(KEY_ERROR, message).build()

    private fun contentIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun foregroundInfo(done: Long, eta: Long?, verifying: Boolean): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Setup", NotificationManager.IMPORTANCE_LOW))
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val sizes = "${ModelSetup.formatBytes(done)} of ${ModelSetup.formatBytes(total)}"
        val text = when {
            verifying -> "Checking files…"
            eta != null -> "$sizes · ${ModelSetup.formatEta(eta)} left"
            else -> sizes
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Setting up MindVault")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "Download"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ETA = "eta"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"
        private const val CHANNEL_ID = "setup"
        private const val NOTIFICATION_ID = 100
        private const val NOTIFICATION_DONE_ID = 101
        private const val FREE_SPACE_MARGIN = 256L shl 20
    }
}
