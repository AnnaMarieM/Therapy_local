package com.therapylocal.app.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.nio.file.Files

/** The downloader on a connection that drops, trickles, ignores Range and fails: a local server misbehaves on purpose. */
class ResumableDownloadTest {
    private val data = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val dir: File = Files.createTempDirectory("dl").toFile()
    private val part = File(dir, "x.part")
    private var server: ServerSocket? = null
    private val requests = ArrayList<String?>()

    /** One request on a raw socket: just what the tests need of HTTP. */
    private class HttpExchange(val socket: Socket) {
        fun sendResponseHeaders(status: Int, length: Long) {
            val reason = when (status) { 200 -> "OK"; 206 -> "Partial Content"; 416 -> "Range Not Satisfiable"; else -> "Error" }
            val head = "HTTP/1.1 $status $reason\r\n" + (if (length >= 0) "Content-Length: $length\r\n" else "Content-Length: 0\r\n") + "Connection: close\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray())
        }
        val responseBody get() = socket.getOutputStream()
        fun close() = runCatching { socket.close() }
    }

    /** [behave] gets the request number (from 0) and the offset asked for, and answers it. */
    private fun serve(behave: (HttpExchange, Int, Int) -> Unit): String {
        val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    val ex = HttpExchange(sock)
                    try {
                        val input = sock.getInputStream().bufferedReader()
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
                        }
                        val n = synchronized(requests) { requests.add(range); requests.size - 1 }
                        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
                        behave(ex, n, from)
                    } catch (_: IOException) {
                    } finally { ex.close() }
                }
            }
        }
        server = s
        return "http://127.0.0.1:${s.localPort}/f"
    }

    @After
    fun stop() {
        server?.close()
        dir.deleteRecursively()
    }

    private fun HttpExchange.body(from: Int, upTo: Int = data.size, status: Int = if (from > 0) 206 else 200) {
        sendResponseHeaders(status, (data.size - from).toLong())
        responseBody.write(data, from, upTo - from)
        responseBody.flush()
    }

    private val fast = ResumableDownload.Config(readTimeoutMs = 2_000, stallBytesPerSec = 1_000, stallWindowMs = 400, maxFailuresWithoutProgress = 5, backoffMs = { 5 })

    @Test
    fun aConnectionThatDropsAgainAndAgainStillEndsWithTheExactFile() = runBlocking {
        // Every connection delivers 50 000 bytes and dies.
        val url = serve { ex, _, from -> ex.body(from, minOf(data.size, from + 50_000)) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
        assertTrue("it resumed instead of starting over", requests.drop(1).all { it != null })
        assertEquals(6, requests.size)
    }

    @Test
    fun aKeptPartialFileIsResumedFromItsEnd() = runBlocking {
        part.writeBytes(data.copyOf(120_000))
        val url = serve { ex, _, from -> ex.body(from) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
        assertEquals(listOf("bytes=120000-"), requests)
    }

    @Test
    fun aServerThatIgnoresRangeSendsEverythingAndTheFileIsStillRight() = runBlocking {
        part.writeBytes(data.copyOf(100_000))
        val url = serve { ex, _, _ -> ex.body(0, status = 200) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun aPartialFileLongerThanTheFileIsThrownAway() = runBlocking {
        part.writeBytes(ByteArray(data.size + 10))
        val url = serve { ex, _, from -> ex.body(from) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
        assertEquals(listOf<String?>(null), requests)
    }

    @Test
    fun aRangeTheServerRefusesStartsAgainFromZero() = runBlocking {
        part.writeBytes(data.copyOf(100_000))
        val url = serve { ex, n, from -> if (n == 0) ex.sendResponseHeaders(416, -1) else ex.body(from) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun aTrickleThatNeverTimesOutIsDroppedAsStalledAndResumed() = runBlocking {
        // First connection: 100 bytes, then a byte every 150 ms. Far below the 1000 bytes/s minimum.
        val url = serve { ex, n, from ->
            if (n == 0) {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.write(data, 0, 100)
                ex.responseBody.flush()
                try { for (i in 100 until 200) { Thread.sleep(150); ex.responseBody.write(data, i, 1); ex.responseBody.flush() } } catch (_: IOException) {}
            } else ex.body(from)
        }
        val started = System.currentTimeMillis()
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
        assertTrue("dropped by the watchdog long before the trickle ended (15 s)", System.currentTimeMillis() - started < 8_000)
        assertTrue(requests.size >= 2)
    }

    @Test
    fun aServerThatKeepsFailingIsGivenUpOnAfterTheLimit() {
        val url = serve { ex, _, _ -> ex.sendResponseHeaders(503, -1) }
        val e = assertThrows(IOException::class.java) { runBlocking { ResumableDownload.fetch(url, part, data.size.toLong(), fast) } }
        assertTrue(e.message!!.contains("503"))
        assertEquals("1 try + 5 retries", 6, requests.size)
    }

    @Test
    fun theFailureCountStartsOverWheneverAnAttemptMakesProgress() = runBlocking {
        // 20 connections in a row each deliver a little and die: far more than the limit of 5, but each one made progress.
        val url = serve { ex, _, from -> ex.body(from, minOf(data.size, from + 15_000)) }
        ResumableDownload.fetch(url, part, data.size.toLong(), fast)
        assertArrayEquals(data, part.readBytes())
        assertEquals(20, requests.size)
    }

    @Test
    fun cancellingStopsAndKeepsWhatWasDownloaded() = runBlocking {
        val url = serve { ex, _, from ->
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.write(data, from, 60_000)
            ex.responseBody.flush()
            try { Thread.sleep(3_000) } catch (_: InterruptedException) {}
        }
        val job: Job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try { ResumableDownload.fetch(url, part, data.size.toLong(), ResumableDownload.Config(readTimeoutMs = 5_000)) } catch (_: CancellationException) {}
        }
        val until = System.currentTimeMillis() + 3_000
        while (part.length() < 60_000 && System.currentTimeMillis() < until) Thread.sleep(20)
        job.cancel()
        job.join()
        assertTrue(part.length() in 60_000L until data.size.toLong())
        assertFalse(File(dir, "x").exists())
    }

    @Test
    fun theStallWatchOnlyTripsOnAFullSlowWindow() {
        val w = StallWatch(minBytesPerSec = 1_000, windowMs = 1_000)
        assertFalse(w.stalled(10, 0))
        assertFalse("window not over yet", w.stalled(10, 900))
        assertTrue("1 s passed with 20 bytes", w.stalled(10, 1_000))
        assertFalse(w.stalled(5_000, 1_500))
        assertFalse("fast window", w.stalled(5_000, 2_000))
    }

    @Test
    fun theBackoffGrowsToAnUpperLimit() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..7).map { ResumableDownload.backoffMs(it) })
    }
}
