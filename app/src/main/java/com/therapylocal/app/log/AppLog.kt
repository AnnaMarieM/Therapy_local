package com.therapylocal.app.log

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.therapylocal.app.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
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
    private var writer: BufferedWriter? = null

    fun init(context: Context) {
        val d = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        dir = d
        executor.execute { openWriter() }
    }

    fun logDir(): File? = dir
    fun logFile(): File? = dir?.resolve("tl.log")

    fun d(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.DEBUG, tag, evt, ctx, f, null)
    fun i(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.INFO, tag, evt, ctx, f, null)
    fun w(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, vararg f: Pair<String, Any?>) = emit(Log.WARN, tag, evt, ctx, f, null)
    fun e(tag: String, evt: String, ctx: LogCtx = LogCtx.NONE, t: Throwable? = null, vararg f: Pair<String, Any?>) =
        emit(Log.ERROR, tag, evt, ctx, f, t)

    /** Content logging, only when [fullTextLogging] is enabled. Otherwise a no-op. */
    fun text(tag: String, evt: String, ctx: LogCtx, content: String) {
        if (fullTextLogging) emit(Log.DEBUG, tag, evt, ctx, arrayOf("text" to content), null)
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
        val sb = StringBuilder(160)
        sb.append("ts=").append(timeFormat.get()!!.format(Date()))
            .append(" lvl=").append(levelChar(level))
            .append(" tag=").append(tag)
            .append(" sid=").append(sessionId)
        ctx.rid?.let { sb.append(" rid=").append(it) }
        ctx.cid?.let { sb.append(" cid=").append(it) }
        sb.append(" evt=").append(evt)
        for ((k, v) in f) {
            val shown = if (v != null && (k == "client" || k == "session")) pseudonymize(v.toString()) else v
            sb.append(' ').append(k).append('=').append(format(shown))
        }
        if (t != null) sb.append(" err=").append(format(t.javaClass.simpleName)).append(" err_msg=").append(format(t.message))
        val line = sb.toString()
        Log.println(level, LOGCAT_TAG, line)
        if (t != null) Log.println(Log.ERROR, LOGCAT_TAG, Log.getStackTraceString(t))
        write(line)
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
                val w = writer ?: openWriter() ?: return@execute
                w.write(line)
                w.write("\n")
                w.flush()
                if ((logFile()?.length() ?: 0L) > MAX_FILE_BYTES) rotate()
            } catch (_: Throwable) {
                // logging must never crash the app
            }
        }
    }

    private fun openWriter(): BufferedWriter? {
        val f = logFile() ?: return null
        return BufferedWriter(FileWriter(f, true)).also { writer = it }
    }

    private fun rotate() {
        writer?.close()
        writer = null
        val d = dir ?: return
        File(d, "tl.${KEEP_FILES - 1}.log").delete()
        for (i in KEEP_FILES - 2 downTo 1) File(d, "tl.$i.log").renameTo(File(d, "tl.${i + 1}.log"))
        File(d, "tl.log").renameTo(File(d, "tl.1.log"))
        openWriter()
    }

    /** Last [maxLines] lines of the current log file, for the in-app viewer. */
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

    fun clear() {
        executor.execute {
            writer?.close()
            writer = null
            dir?.listFiles()?.forEach { it.delete() }
            openWriter()
        }
    }
}
