package com.therapylocal.app.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.therapylocal.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable, checksummed download of every file of one [ModelSpec] into app-private external storage.
 * Files that are already complete are skipped, so a model is never pulled twice.
 */
class ModelDownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private lateinit var spec: ModelSpec
    private var lastNotified = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        spec = ModelRegistry.byId(inputData.getString(KEY_SPEC)!!)
        val ctx = com.therapylocal.app.log.LogCtx.NONE
        if (spec.isReady(context)) {
            AppLog.i(TAG, "download_skip", ctx, "model" to spec.id, "reason" to "already_complete")
            return@withContext Result.success()
        }

        val missing = spec.files.sumOf { f ->
            val part = spec.part(context, f)
            if (spec.file(context, f).exists()) 0L else f.size - (if (part.exists()) part.length() else 0L)
        }
        val free = spec.dir(context).usableSpace
        if (free < missing + FREE_SPACE_MARGIN) {
            val msg = "Not enough storage: need ${missing shr 20} MB, have ${free shr 20} MB"
            AppLog.e(TAG, "download_failed", ctx, null, "model" to spec.id, "reason" to "no_space", "need_mb" to (missing shr 20), "free_mb" to (free shr 20))
            return@withContext Result.failure(error(msg))
        }

        setForeground(foregroundInfo(0))
        try {
            AppLog.span(TAG, "model_download", ctx, "model" to spec.id, "total_bytes" to spec.totalBytes, "to_fetch_bytes" to missing) { span ->
                var doneBefore = 0L
                for (f in spec.files) {
                    val target = spec.file(context, f)
                    if (target.exists() && target.length() == f.size) {
                        doneBefore += f.size
                        continue
                    }
                    downloadFile(f, doneBefore)
                    report(PHASE_VERIFYING, doneBefore + f.size)
                    val sha = AppLog.span(TAG, "model_verify", ctx, "model" to spec.id, "file" to f.name, "bytes" to f.size) { sha256(spec.part(context, f)) }
                    if (sha != f.sha256) {
                        spec.part(context, f).delete()
                        AppLog.e(TAG, "checksum_mismatch", ctx, null, "model" to spec.id, "file" to f.name, "got" to sha)
                        return@span Result.failure(error("Checksum mismatch for ${f.name}"))
                    }
                    check(spec.part(context, f).renameTo(target)) { "Could not move ${f.name} into place" }
                    doneBefore += f.size
                }
                span.put("fetched_bytes", missing)
                Result.success()
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "download_interrupted", ctx, "model" to spec.id, "err" to e.message, "will_retry" to true)
            Result.retry()
        }
    }

    private suspend fun downloadFile(f: ModelFile, doneBefore: Long) {
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
            AppLog.i(TAG, "download_file_start", com.therapylocal.app.log.LogCtx.NONE, "model" to spec.id, "file" to f.name, "bytes" to f.size, "resumed_from" to if (resumed) have else 0L)
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
                                AppLog.d(TAG, "download_progress", com.therapylocal.app.log.LogCtx.NONE, "model" to spec.id, "file" to f.name, "pct" to pct)
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

    private suspend fun report(phase: String, done: Long) {
        setProgress(Data.Builder().putString(KEY_PHASE, phase).putLong(KEY_DONE, done).putLong(KEY_TOTAL, spec.totalBytes).build())
        val now = System.currentTimeMillis()
        if (now - lastNotified > 1500) {
            lastNotified = now
            setForeground(foregroundInfo(done))
        }
    }

    private fun error(message: String): Data = Data.Builder().putString(KEY_ERROR, message).build()

    private fun foregroundInfo(done: Long): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW))
        val total = spec.totalBytes
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.title}")
            .setContentText("$pct%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_BASE + (spec.id.hashCode() and 0xff), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "Download"
        const val KEY_SPEC = "spec"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"
        fun uniqueName(spec: ModelSpec) = "model-download-${spec.id}"
        private const val CHANNEL_ID = "model-download"
        private const val NOTIFICATION_BASE = 100
        private const val FREE_SPACE_MARGIN = 256L shl 20
    }
}
