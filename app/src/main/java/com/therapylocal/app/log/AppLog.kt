package com.therapylocal.app.log

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.therapylocal.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Correlation ids carried on every line: rid = recording id, cid = chunk id ("<rid>.<idx>"). */
data class LogCtx(val rid: Long? = null, val cid: String? = null) {
    fun chunk(idx: Int) = LogCtx(rid, "${rid ?: 0}.$idx")

    companion object {
        val NONE = LogCtx()
    }
}

/** A timed stage. Add result fields with [put]; they are written on the span_end line. */
class Span {
    internal val extra = LinkedHashMap<String, Any?>()
    fun put(key: String, value: Any?) {
        extra[key] = value
    }
}

/**
 * Structured key=value logging to logcat (tag "TL") and a rotating file in the app's external files dir.
 *
 * Line format: ts=... lvl=I tag=ASR sid=ab12cd rid=3 cid=3.2 evt=span_end span=transcribe dur_ms=1234 ...
 * Transcript or audio content is never logged at INFO. Fields "client" and "session" are written as [pseudonymize]d values. [text] writes it only when [fullTextLogging] is on.
 */
object AppLog {
    const val LOGCAT_TAG = "TL"
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_PINNED_BYTES = 400_000L
    private const val BACKEND_TAG = "Backend"
    private const val KEEP_FILES = 5

    val sessionId: String = UUID.randomUUID().toString().take(6)

    /** Off by default (therapy app). Set the LOG_TEXT build flag in app/build.gradle.kts to enable. */
    @Volatile
    var fullTextLogging: Boolean = BuildConfig.LOG_TEXT

    /** Maps patient identifiers (fields "client", "session") to a stable pseudonym; set by the Vault on unlock. */
    @Volatile
    var pseudonymize: (String) -> String = { "redacted" }

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US) }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "applog-writer").apply { isDaemon = true } }
    private var dir: File? = null
    private var log: RotatingLog? = null

    /** `main`, `live` or `batch`: on every line as `proc=`. */
    @Volatile
    var proc: String = "main"
        private set
    private val pid: Int = android.os.Process.myPid()

    /** This process's session id, read-only: a host's `host_start` carries main's as `client_sid` so the files join. */
    val sid: String get() = sessionId

    private val isMainProc get() = proc == "main"

    /** The file base of a process: `tl` for main, `engine-live` / `engine-batch` for the hosts (same rotation). */
    fun baseOf(proc: String) = if (proc == "main") "tl" else "engine-$proc"
    private val HOST_BASES = listOf("engine-live", "engine-batch")

    fun init(context: Context, proc: String = "main") {
        this.proc = proc
        val d = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        dir = d
        log = RotatingLog(d, baseOf(proc), MAX_FILE_BYTES, KEEP_FILES)
        executor.execute { runCatching { log?.open() } }
    }

    /**
     * Writes any uncaught exception to the log file before the process dies, then lets Android handle it as usual. With [redacted] (engine
     * processes, whose code holds patient text) only exception types, cause types and frames are written, never messages. Nothing here reads
     * `ApplicationExitInfo.getTraceInputStream()`.
     */
    fun installCrashHandler(redacted: Boolean = false) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val head = "ts=${timeFormat.get()!!.format(Date())} lvl=E tag=CRASH sid=$sessionId proc=$proc pid=$pid evt=uncaught thread=${format(thread.name)} err=${format(t.javaClass.name)}"
                val text = if (redacted) {
                    head + redactedFields(t).joinToString("") { (k, v) -> " $k=${format(v)}" }
                } else {
                    head + "\n" + Log.getStackTraceString(t)
                }
                Log.println(Log.ERROR, LOGCAT_TAG, text)
                if (dir != null) {
                    executor.submit {
                        try {
                            log?.write(text)
                        } catch (_: Throwable) {
                        }
                    }.get(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Throwable) {
                // the crash handler must never hide the original crash
            }
            // The platform handler prints the message and stack to logcat and DropBox. In a process that holds patient text, hand it a copy with
            // the class name and the frames only, so an exception message that quotes a prompt or transcript never reaches them.
            previous?.uncaughtException(thread, if (redacted) RuntimeException(t.javaClass.name).also { it.stackTrace = t.stackTrace } else t)
        }
    }

    fun logDir(): File? = dir
    fun logFile(): File? = log?.file

    /** Every `*.log` in the logs dir (all processes, rotated ones too), for `adb pull`. */
    fun logFiles(): List<File> = dir?.listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.name } ?: emptyList()

    fun logMemory(context: Context, tag: String = "Memory", evt: String = "snapshot", ctx: LogCtx = LogCtx.NONE, vararg extra: Pair<String, Any?>) {
        val snapshot = com.therapylocal.app.memory.DeviceMemory.memorySnapshot(context)
        val combined = snapshot + extra
        emit(Log.INFO, tag, evt, ctx, combined, null)
    }

    fun d(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.DEBUG, tag, evt, ctx, f, null)
    fun i(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.INFO, tag, evt, ctx, f, null)
    fun w(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.WARN, tag, evt, ctx, f, null)
    fun e(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, t: Throwable? = null, vararg f: Pair<String, Any?>) =
        emit(Log.ERROR, tag, evt, ctx, f, t)

    /**
     * An error from code that handles patient text (inference, embedding, transcription): its message and stack trace can quote that text, so
     * only the type, the cause types and the code locations are written.
     */
    fun eRedacted(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, t: Throwable, vararg f: Pair<String, Any?>) =
        emit(Log.ERROR, tag, evt, ctx, arrayOf(*f, *redactedFields(t)), null)

    /**
     * Waits (at most [timeoutMs]) until every line written so far is in the file. For the line before a step that can kill the process
     * natively (a model engine starting on the GPU): lines are written on a background thread, and a kill would otherwise lose the last ones.
     */
    fun flush(timeoutMs: Long = 500) {
        if (dir == null) return
        try {
            executor.submit { log?.flush() }.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // a slow disk must not hold up the load; the line is still queued
        }
    }

    @PublishedApi
    internal fun redactedFields(t: Throwable): Array<Pair<String, Any?>> = arrayOf(
        "err" to com.therapylocal.app.models.BackendLadder.fullName(t),
        "cause_chain" to com.therapylocal.app.models.BackendLadder.causeChain(t, messages = false),
        "frames" to com.therapylocal.app.models.BackendLadder.frames(t),
        "is_oom" to (t is OutOfMemoryError),
    )

    /** Content logging, only when [fullTextLogging] is enabled. Otherwise a no-op. */
    fun text(tag: String, evt: String, ctx: LogCtx, content: String) {
        if (fullTextLogging) emit(Log.DEBUG, tag, evt, ctx, arrayOf("text" to content), null)
    }

    /** [span] for stages that handle patient text: a failure is logged as with [eRedacted], never with its message. */
    inline fun <T> spanRedacted(
        tag: String,
        name: String,
        ctx: LogCtx = LogCtx.NONE,
        vararg fields: Pair<String, Any?>,
        block: (Span) -> T,
    ): T {
        val span = Span()
        val start = SystemClock.elapsedRealtimeNanos()
        spanEvent(Log.DEBUG, tag, "span_start", name, ctx, fields, null, null)
        try {
            val result = block(span)
            spanEvent(Log.INFO, tag, "span_end", name, ctx, fields, span, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            return result
        } catch (t: Throwable) {
            spanEvent(Log.ERROR, tag, "span_error", name, ctx, arrayOf(*fields, *redactedFields(t)), span, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            throw t
        }
    }

    /** Wraps [block] in a traced stage: span_start (debug), then span_end (info) with dur_ms, or span_error. */
    inline fun <T> span(
        tag: String,
        name: String,
        ctx: LogCtx = LogCtx.NONE,
        vararg fields: Pair<String, Any?>,
        block: (Span) -> T,
    ): T {
        val span = Span()
        val start = SystemClock.elapsedRealtimeNanos()
        spanEvent(Log.DEBUG, tag, "span_start", name, ctx, fields, null, null)
        try {
            val result = block(span)
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            spanEvent(Log.INFO, tag, "span_end", name, ctx, fields, span, ms)
            return result
        } catch (t: Throwable) {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            spanEvent(Log.ERROR, tag, "span_error", name, ctx, fields, span, ms, t)
            throw t
        }
    }

    @PublishedApi
    internal fun spanEvent(
        level: Int, tag: String, evt: String, name: String, ctx: LogCtx,
        fields: Array<out Pair<String, Any?>>, span: Span?, durMs: Double?, t: Throwable? = null,
    ) {
        val all = ArrayList<Pair<String, Any?>>(fields.size + 4)
        all += "span" to name
        if (durMs != null) all += "dur_ms" to "%.1f".format(Locale.US, durMs)
        all.addAll(fields)
        span?.extra?.forEach { (k, v) -> all += k to v }
        emit(level, tag, evt, ctx, all.toTypedArray(), t)
    }

    private fun emit(level: Int, tag: String, evt: String, ctx: LogCtx, f: Array<out Pair<String, Any?>>, t: Throwable?) {
        val line = line(timeFormat.get()!!.format(Date()), level, tag, proc, pid, ctx, evt, f, t)
        Log.println(level, LOGCAT_TAG, line)
        if (t != null) runCatching { Log.println(Log.ERROR, LOGCAT_TAG, Log.getStackTraceString(t)) }
        // Debug lines (span starts) stay in logcat outside debug builds: in the file they would rotate the useful lines away.
        if (level > Log.DEBUG || BuildConfig.DEBUG) write(line)
        // Which hardware each model ran on, and why: kept apart so hours of recording never rotate it away. Main only: a host's lines reach it
        // through the ladder main logs (same load_id).
        if (isMainProc && level >= Log.INFO && (tag == BACKEND_TAG || f.any { it.first == "load_id" })) writePinned(line)
    }

    /** One formatted line. Internal so the format can be tested without Android. */
    internal fun line(
        ts: String, level: Int, tag: String, proc: String, pid: Int, ctx: LogCtx, evt: String,
        f: Array<out Pair<String, Any?>>, t: Throwable?,
    ): String {
        val sb = StringBuilder(160)
        sb.append("ts=").append(ts)
            .append(" lvl=").append(levelChar(level))
            .append(" tag=").append(tag)
            .append(" sid=").append(sessionId)
            .append(" proc=").append(proc)
            .append(" pid=").append(pid)
        ctx.rid?.let { sb.append(" rid=").append(it) }
        ctx.cid?.let { sb.append(" cid=").append(it) }
        sb.append(" evt=").append(evt)
        for ((k, v) in f) {
            val shown = if (v != null && (k == "client" || k == "session")) pseudonymize(v.toString()) else v
            sb.append(' ').append(k).append('=').append(format(shown))
        }
        if (t != null) sb.append(" err=").append(format(com.therapylocal.app.models.BackendLadder.name(t))).append(" err_msg=").append(format(runCatching { t.message?.take(2000) }.getOrElse { "(message unreadable)" }))
        return sb.toString()
    }

    /** `backend.log`: the backend lines only, never rotated away; past [MAX_PINNED_BYTES] its older half is dropped. */
    fun pinnedFile(): File? = dir?.resolve("backend.log")

    private fun writePinned(line: String) {
        val f = pinnedFile() ?: return
        executor.execute {
            try {
                f.appendText(line + "\n")
                if (f.length() > MAX_PINNED_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
                }
            } catch (_: Throwable) {
                // logging must never crash the app
            }
        }
    }

    private fun levelChar(level: Int) = when (level) {
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'V'
    }

    private fun format(v: Any?): String {
        val s = when (v) {
            null -> "null"
            is Float -> "%.4f".format(Locale.US, v)
            is Double -> "%.4f".format(Locale.US, v)
            else -> v.toString()
        }
        return if (s.isEmpty() || s.any { it == ' ' || it == '"' || it == '=' || it == '\n' }) {
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        } else {
            s
        }
    }

    private fun write(line: String) {
        if (dir == null) return
        executor.execute {
            try {
                log?.write(line)
            } catch (_: Throwable) {
                // logging must never crash the app
            }
        }
    }

    /** Last [maxLines] lines of this process's current log file. */
    fun tail(maxLines: Int = 400): String {
        val f = logFile() ?: return ""
        if (!f.exists()) return ""
        val lines = ArrayDeque<String>()
        f.forEachLine { l ->
            lines.addLast(l)
            if (lines.size > maxLines) lines.removeFirst()
        }
        return lines.joinToString("\n")
    }

    /** The last [n] lines of `tl.log`, `engine-live.log` and `engine-batch.log` merged by `ts=`, so host lines show in order (the log viewer and share). */
    fun tailAll(n: Int = 400): String {
        val d = dir ?: return ""
        return LogMerge.tailAll(d, listOf("tl") + HOST_BASES, n)
    }

    /**
     * Deletes this process's own files only (in main: `tl*.log` and `backend.log`). Main then has the bound hosts clear theirs and deletes the
     * files of hosts that are not running; a host's writer re-opens a new file on its next line.
     */
    fun clear() {
        executor.execute {
            runCatching {
                log?.clear()
                if (isMainProc) dir?.resolve("backend.log")?.delete()
            }
        }
        if (isMainProc) {
            val d = dir ?: return
            // Asking a bound host to clear its files can take seconds (it waits for the answer): never on the single writer thread, which every log line needs.
            Thread({ runCatching { com.therapylocal.app.embed.Engines.clearHostLogs { role -> RotatingLog.deleteBase(d, baseOf(role.proc)) } } }, "applog-clear-hosts")
                .apply { isDaemon = true }.start()
        }
    }
}
