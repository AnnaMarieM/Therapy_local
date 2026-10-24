package com.therapylocal.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Resumable download of the Gemma model into app-private external storage. */
class ModelDownloadWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val target = ModelConfig.file(context)
        val part = ModelConfig.partFile(context)
        if (target.exists() && target.length() == ModelConfig.SIZE_BYTES) return@withContext Result.success()

        val needed = ModelConfig.SIZE_BYTES - (if (part.exists()) part.length() else 0L)
        val free = part.parentFile!!.usableSpace
        if (free < needed + FREE_SPACE_MARGIN) {
            return@withContext Result.failure(error("Not enough storage: need ${needed shr 20} MB, have ${free shr 20} MB"))
        }

        setForeground(foregroundInfo(0, ModelConfig.SIZE_BYTES, "Downloading model"))
        try {
            download(part)
            report(PHASE_VERIFYING, ModelConfig.SIZE_BYTES, ModelConfig.SIZE_BYTES)
            val sha = sha256(part)
            if (sha != ModelConfig.SHA256) {
                part.delete()
                return@withContext Result.failure(error("Checksum mismatch ($sha)"))
            }
            if (!part.renameTo(target)) return@withContext Result.failure(error("Could not move model into place"))
            target.parentFile!!.listFiles()?.filter { it != target }?.forEach { it.delete() } // drop older model versions
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "download interrupted, will retry", e)
            Result.retry()
        }
    }

    private suspend fun download(part: java.io.File) {
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(ModelConfig.URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!resumed && conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            var done = if (resumed) have else 0L
            var lastReport = 0L
            java.io.FileOutputStream(part, resumed).use { out ->
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
                            report(PHASE_DOWNLOADING, done, ModelConfig.SIZE_BYTES)
                            setForeground(foregroundInfo(done, ModelConfig.SIZE_BYTES, "Downloading model"))
                        }
                    }
                }
            }
            if (done != ModelConfig.SIZE_BYTES) throw IOException("Short download: $done of ${ModelConfig.SIZE_BYTES}")
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: java.io.File): String {
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

    private suspend fun report(phase: String, done: Long, total: Long) = setProgress(
        Data.Builder().putString(KEY_PHASE, phase).putLong(KEY_DONE, done).putLong(KEY_TOTAL, total).build()
    )

    private fun error(message: String): Data = Data.Builder().putString(KEY_ERROR, message).build()

    private fun foregroundInfo(done: Long, total: Long, title: String): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW)
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$pct%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "ModelDownload"
        const val UNIQUE_NAME = "model-download"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"
        private const val CHANNEL_ID = "model-download"
        private const val NOTIFICATION_ID = 1
        private const val FREE_SPACE_MARGIN = 256L shl 20
    }
}
