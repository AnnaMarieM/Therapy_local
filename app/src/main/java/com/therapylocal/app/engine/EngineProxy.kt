package com.therapylocal.app.engine

import android.content.Context
import android.os.Looper
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Backends
import java.util.UUID

/**
 * The generic main-side call path and the only code that calls [EngineHost.call]. `MemoryLlm`, `Embedder` and `SpeechRecogniser` hold one
 * by composition. It logs `engine_call` / `engine_call_failed` (redacted for text ops), turns phases the host reports into guard notes in
 * main, and checks what comes back. It imports nothing native.
 *
 * [onPhase] is where the host's phases land (by default [Backends.phase]: guard note, log, flush); tests pass a recorder.
 */
class EngineProxy(
    private val host: () -> EngineHost,
    private val tag: String,
    private val engine: String,
    private val onPhase: (guardModel: String, backend: String, phase: String) -> Unit,
) {
    constructor(context: Context, host: () -> EngineHost, tag: String, engine: String) :
        this(host, tag, engine, { model, backend, phase -> Backends.phase(context, tag, model, backend, phase, host().guardRole) })

    fun newTag(op: String, loadId: String? = null) = CallTag(UUID.randomUUID().toString().take(8), loadId, op)

    /** Runs [op] and returns the raw reply. Prefer [call]. */
    fun send(op: EngineOp, phases: PhaseSink = PhaseSink { _, _, _, _ -> }): EngineReply {
        checkNotMainThread(op)
        val h = host()
        val t0 = System.nanoTime()
        try {
            val r = h.call(op, phases)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val fields = arrayOf<Pair<String, Any?>>(
                "engine" to engine, "role" to h.role.proc, "req" to op.tag.requestId, "load_id" to op.tag.loadId, "op" to op.tag.op, "dur_ms" to ms, "ok" to true,
            )
            if (op is EngineOp.EmbedText || op is EngineOp.EmbedAudio || op is EngineOp.Transcribe) {
                AppLog.d(tag, EngineEvents.CALL, LogCtx.NONE, *fields)
            } else {
                AppLog.i(tag, EngineEvents.CALL, LogCtx.NONE, *fields)
            }
            return r
        } catch (t: Throwable) {
            val fields = arrayOf<Pair<String, Any?>>(
                "engine" to engine, "role" to h.role.proc, "req" to op.tag.requestId, "load_id" to op.tag.loadId, "op" to op.tag.op,
                "dur_ms" to (System.nanoTime() - t0) / 1_000_000, "ok" to false,
            )
            runCatching {
                // Text ops can quote the prompt, transcript or query in a message: types and frames only.
                if (isTextOp(op)) AppLog.eRedacted(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, t, *fields)
                else AppLog.e(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, t, *fields)
            }
            throw t
        }
    }

    /** [send], with the reply cast to [R]. */
    inline fun <reified R : EngineReply> call(op: EngineOp, phases: PhaseSink = PhaseSink { _, _, _, _ -> }): R {
        val r = send(op, phases)
        return r as? R ?: throw IllegalStateException("engine replied ${r.javaClass.simpleName} to ${op.tag.op}, expected ${R::class.java.simpleName}")
    }

    /** One ladder attempt as `BackendLadder`'s open lambda: Open to Opened; the host's phases go to [onPhase] (guard note, log, flush) in main. */
    fun open(
        kind: EngineKind, model: String, guardModel: String, backend: String, loadId: String? = null,
        maxTokens: Int = 0, textOnly: Boolean = false, language: String? = null,
    ): EngineReply.Opened =
        call<EngineReply.Opened>(
            EngineOp.Open(newTag("open", loadId), kind, model, guardModel, backend, maxTokens, textOnly, language),
            PhaseSink { _, _, b, phase -> onPhase(guardModel, b, phase) },
        )

    /** Closes a handle and waits for it. For ladder cleanup on a worker thread; use [close] anywhere else. */
    fun closeNow(handle: Int, loadId: String? = null) {
        call<EngineReply.Done>(EngineOp.Close(newTag("close", loadId), handle))
    }

    /** Never blocks: [EngineHost.closeAsync]. The one operation main-thread code may call. Failures are logged, never thrown. */
    fun close(handle: Int, loadId: String? = null, onClosed: (acked: Boolean) -> Unit = {}) {
        runCatching { host().closeAsync(EngineOp.Close(newTag("close", loadId), handle), onClosed) }
            .onFailure {
                runCatching { AppLog.e(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, it, "engine" to engine, "op" to "close", "handle" to handle) }
                runCatching { onClosed(false) }
            }
    }

    /** Waits (bounded) for the closes submitted so far, before a memory gate or an `Open`. For worker threads. */
    fun awaitPendingCloses() {
        runCatching { host().awaitPendingCloses() }
    }

    /** The host's PSS and handles; null if it cannot say. Binds a host if none is. Logging calls use [statusIfBound]. */
    fun status(): EngineReply.HostStatus? = runCatching { call<EngineReply.HostStatus>(EngineOp.Status(newTag("status"))) }.getOrNull()

    /** [status] of a host that is bound now; never binds one (for logging after an unload). */
    fun statusIfBound(): EngineReply.HostStatus? = runCatching { host().statusIfBound() }.getOrNull()

    /** Checks a Vector reply: [expectedDim] long and every value finite, else `IllegalStateException("bad embedding")`. */
    fun vector(reply: EngineReply.Vector, expectedDim: Int): FloatArray {
        val v = reply.v
        check(v.size == expectedDim && v.all { it.isFinite() }) { "bad embedding" }
        return v
    }

    private fun checkNotMainThread(op: EngineOp) {
        val main = Looper.getMainLooper()
        val onMain = main != null && Looper.myLooper() === main
        if (onMain || Thread.currentThread().name.startsWith("binder:")) {
            val e = IllegalStateException("engine call ${op.tag.op} on thread ${Thread.currentThread().name}")
            runCatching { AppLog.e(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, e, "engine" to engine, "op" to op.tag.op, "why" to "wrong_thread") }
            throw e
        }
    }

    private fun isTextOp(op: EngineOp) = op is EngineOp.Ask || op is EngineOp.EmbedText || op is EngineOp.EmbedAudio || op is EngineOp.Transcribe
}

/**
 * The process an engine really lives in, as the guard's notes name it: `main` for the in-process host (tests, probes, the emergency switch,
 * whatever role it was asked for), else the host's own role. A kill is judged by that process's exit record.
 */
val EngineHost.guardRole: String get() = if (this is LocalEngineHost) com.therapylocal.app.models.BackendGuard.ROLE_MAIN else role.proc
