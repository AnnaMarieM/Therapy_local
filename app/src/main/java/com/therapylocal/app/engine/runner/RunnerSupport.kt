package com.therapylocal.app.engine.runner

import android.util.Log
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.log.Span

/** One loaded native engine in a host: what [com.therapylocal.app.engine.EngineServer] keeps per handle. */
internal interface Runner {
    val kind: EngineKind
    val model: String
    val backend: String

    /** Releases the native engine. Never throws; logs its own failures. */
    fun close(tag: CallTag)
}

internal typealias Fields = Array<Pair<String, Any?>>

/** The fields every host-side line of one call carries (`req`, `load_id`, `op`, `handle`), the same names the main side uses. */
internal fun callFields(tag: CallTag, handle: Int? = null): Fields {
    val f = ArrayList<Pair<String, Any?>>(4)
    f += "req" to tag.requestId
    tag.loadId?.let { f += "load_id" to it }
    f += "op" to tag.op
    handle?.let { f += "handle" to it }
    return f.toTypedArray()
}

/**
 * Before each native step ("create", "initialize", "test"): main hears of it first (synchronously, so its guard names the step if the
 * next one kills this process), then the existing `backend_phase` line is written and flushed to disk. The host never touches the guard.
 */
internal fun notePhase(sink: PhaseSink, tag: CallTag, tagName: String, model: String, backend: String, phase: String) {
    runCatching { sink.phase(tag, model, backend, phase) }
    AppLog.i(tagName, "backend_phase", LogCtx.NONE, "model" to model, "backend_try" to backend, "phase" to phase, *callFields(tag))
    AppLog.flush()
}

/**
 * [AppLog.spanRedacted] whose start and end lines are DEBUG when [debug] is set: the `:live` embed ticks every 2 s, and an INFO line
 * each would rotate the useful lines out of the log file. A failure stays an ERROR line, redacted.
 */
internal inline fun <T> redactedSpan(debug: Boolean, tag: String, name: String, vararg fields: Pair<String, Any?>, block: (Span) -> T): T {
    if (!debug) return AppLog.spanRedacted(tag, name, LogCtx.NONE, *fields, block = block)
    val span = Span()
    val start = android.os.SystemClock.elapsedRealtimeNanos()
    AppLog.spanEvent(Log.DEBUG, tag, "span_start", name, LogCtx.NONE, fields, null, null)
    try {
        val r = block(span)
        AppLog.spanEvent(Log.DEBUG, tag, "span_end", name, LogCtx.NONE, fields, span, (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
        return r
    } catch (t: Throwable) {
        AppLog.spanEvent(
            Log.ERROR, tag, "span_error", name, LogCtx.NONE, arrayOf(*fields, *AppLog.redactedFields(t)), span,
            (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0,
        )
        throw t
    }
}
