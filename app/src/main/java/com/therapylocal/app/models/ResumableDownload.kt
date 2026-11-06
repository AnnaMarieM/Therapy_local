package com.therapylocal.app.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Notices a connection that is up but not delivering: fewer than [minBytesPerSec] bytes on average over a [windowMs] window. A read that
 * blocks outright is caught by the socket's read timeout; this catches the trickle that never quite times out.
 */
class StallWatch(private val minBytesPerSec: Long, private val windowMs: Long) {
    private var start = -1L
    private var bytes = 0L

    /** Feed every read; true once a full window passed too slowly. */
    fun stalled(n: Int, now: Long): Boolean {
        if (start < 0) start = now
        bytes += n
        val elapsed = now - start
        if (elapsed < windowMs) return false
        val slow = bytes * 1000 / elapsed < minBytesPerSec
        start = now
        bytes = 0
        return slow
    }
}

/**
 * Downloads [url] into [part] until it holds exactly the expected size, over a connection that drops all the time: every attempt resumes
 * from the bytes already on disk (Range), a dropped or stalled connection is retried at once after a short pause that grows only while
 * attempts make no progress, and a bad partial file is thrown away. Gives up (IOException) only after [Config.maxFailuresWithoutProgress]
 * attempts in a row that fetched nothing. Cancelling the coroutine stops it and keeps the partial file.
 */
object ResumableDownload {
    class Config(
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 15_000,
        val stallBytesPerSec: Long = 16 * 1024,
        val stallWindowMs: Long = 30_000,
        val maxFailuresWithoutProgress: Int = 15,
        val backoffMs: (failures: Int) -> Long = ::backoffMs,
    )

    /** 1 s after the first failure without progress, doubling up to 30 s. */
    fun backoffMs(failures: Int): Long = (1000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000)

    suspend fun fetch(
        url: String,
        part: File,
        size: Long,
        config: Config = Config(),
        log: (event: String, fields: Map<String, Any?>) -> Unit = { _, _ -> },
        onProgress: suspend (done: Long) -> Unit = {},
    ) {
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            var have = if (part.exists()) part.length() else 0L
            if (have > size) {
                log("part_too_long", mapOf("have" to have, "size" to size))
                part.delete()
                have = 0
            }
            if (have == size) return
            try {
                attempt(url, part, size, have, config, log, onProgress)
                if (part.length() == size) return
                throw IOException("Short download: ${part.length()} of $size")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failures = if (part.length() > have) 1 else failures + 1
                log("attempt_failed", mapOf("err" to e.message, "have" to part.length(), "failures" to failures))
                if (failures > config.maxFailuresWithoutProgress) throw e
                delay(config.backoffMs(failures))
            }
        }
    }

    private suspend fun attempt(url: String, part: File, size: Long, have: Long, config: Config, log: (String, Map<String, Any?>) -> Unit, onProgress: suspend (Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> {}
                code == HttpURLConnection.HTTP_OK -> {}
                code == 416 -> { part.delete(); throw IOException("HTTP 416: the partial file does not fit, starting again") }
                else -> throw IOException("HTTP $code")
            }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            var done = if (resumed) have else 0L
            log("attempt_start", mapOf("resumed_from" to done, "size" to size))
            val watch = StallWatch(config.stallBytesPerSec, config.stallWindowMs)
            var lastReport = 0L
            FileOutputStream(part, resumed).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (watch.stalled(n, now)) throw IOException("Stalled: under ${config.stallBytesPerSec} bytes/s for ${config.stallWindowMs / 1000} s")
                        if (now - lastReport > 500) { lastReport = now; onProgress(done) }
                    }
                }
            }
            onProgress(done)
        } finally {
            conn.disconnect()
        }
    }
}
