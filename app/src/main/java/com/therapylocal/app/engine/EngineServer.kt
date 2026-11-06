package com.therapylocal.app.engine

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.therapylocal.app.engine.runner.CanaryRunner
import com.therapylocal.app.engine.runner.EmbedRunner
import com.therapylocal.app.engine.runner.LlmRunner
import com.therapylocal.app.engine.runner.Runner
import com.therapylocal.app.engine.runner.callFields
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The dispatch both hosts share ([LocalEngineHost] in this process, `EngineHostService` in `:live` and `:batch`): an [EngineOp] in, an
 * [EngineReply] out, the native work done by the runners. It never touches `Backends.guard`, `PerfStats`, preferences or the vault: that
 * bookkeeping belongs to the main process, which hears of each native step through the [PhaseSink].
 *
 * [lowerPriority] (finding 18): a host process puts the calling binder thread at background priority before `Open`, so the native pools
 * created there (LiteRT, XNNPACK, ORT) inherit it. In-process it would lower a main-process thread for good, so [LocalEngineHost] passes false.
 */
class EngineServer(private val context: Context, private val role: EngineRole, private val lowerPriority: Boolean = false) {
    private val handles = ConcurrentHashMap<Int, Runner>()
    private val nextHandle = AtomicInteger(1)

    fun handle(op: EngineOp, phases: PhaseSink = PhaseSink { _, _, _, _ -> }): EngineReply {
        if (lowerPriority && lowersPriority(op)) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        return when (op) {
            is EngineOp.Open -> open(op, phases)
            is EngineOp.Ask -> {
                val llm = runner<LlmRunner>(op.handle, op.tag)
                synchronized(llm) { AppLog.spanRedacted(TAG, "host_ask", LogCtx.NONE, *callFields(op.tag, op.handle)) { llm.ask(op) } }
            }
            is EngineOp.EmbedText -> {
                val e = runner<EmbedRunner>(op.handle, op.tag)
                synchronized(e) { e.embedText(op) }
            }
            is EngineOp.EmbedAudio -> {
                val e = runner<EmbedRunner>(op.handle, op.tag)
                synchronized(e) { e.embedAudio(op) }
            }
            is EngineOp.Transcribe -> {
                val c = runner<CanaryRunner>(op.handle, op.tag)
                synchronized(c) { c.transcribe(op) }
            }
            is EngineOp.Close -> close(op)
            is EngineOp.Status -> status()
            is EngineOp.ClearLogs -> {
                AppLog.clear()
                EngineReply.Done
            }
            is EngineOp.ArmFaults -> {
                AppLog.w(TAG, EngineEvents.FAULT_PLAN_IGNORED, LogCtx.NONE, *callFields(op.tag))
                EngineReply.Refused
            }
        }
    }

    /** Every op in a batch host, and the ops that load or run the embedder in a live host (the rest there are cheap). */
    private fun lowersPriority(op: EngineOp) = when (role) {
        EngineRole.BATCH -> true
        EngineRole.LIVE -> op is EngineOp.Open || op is EngineOp.EmbedText || op is EngineOp.EmbedAudio
        EngineRole.LOCAL -> false
    }

    private fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply {
        if (!serves(op)) {
            AppLog.w(TAG, EngineEvents.ROLE_VIOLATION, LogCtx.NONE, "kind" to op.kind.name, "text_only" to op.textOnly, "role" to role.proc, *callFields(op.tag))
            throw IllegalArgumentException("kind ${op.kind} not served by role ${role.proc}")
        }
        val f = callFields(op.tag)
        AppLog.i(TAG, EngineEvents.HOST_OPEN, LogCtx.NONE, "model" to op.model, "backend_try" to op.backend, "kind" to op.kind.name, *f)
        return AppLog.span(TAG, "host_open", LogCtx.NONE, "model" to op.model, "backend_try" to op.backend, *f) { span ->
            // Each runner closes whatever it created if a step fails, so a failed attempt leaves nothing behind and no handle.
            val (runner, opened) = when (op.kind) {
                EngineKind.LLM -> LlmRunner(context).let { it to it.open(op, phases) }
                EngineKind.EMBED -> EmbedRunner(context, role, op.textOnly).let { it to it.open(op, phases) }
                EngineKind.ASR -> CanaryRunner(context).let { it to it.open(op, phases) }
            }
            val handle = nextHandle.getAndIncrement()
            handles[handle] = runner
            span.put("handle", handle)
            opened.copy(handle = handle)
        }
    }

    /** LIVE: the embedder with the audio encoder. BATCH: the LLM, the text-only embedder and speech recognition. LOCAL: everything. */
    private fun serves(op: EngineOp.Open): Boolean = when (role) {
        EngineRole.LOCAL -> true
        EngineRole.LIVE -> op.kind == EngineKind.EMBED && !op.textOnly
        EngineRole.BATCH -> op.kind == EngineKind.LLM || op.kind == EngineKind.ASR || (op.kind == EngineKind.EMBED && op.textOnly)
    }

    private inline fun <reified R : Runner> runner(handle: Int, tag: CallTag): R {
        val r = handles[handle] ?: throw IllegalStateException("unknown engine handle $handle")
        return r as? R ?: throw IllegalArgumentException("handle $handle is a ${r.kind} engine, not served by op ${tag.op}")
    }

    private fun close(op: EngineOp.Close): EngineReply {
        val r = handles.remove(op.handle)
        if (r == null) {
            AppLog.i(TAG, "host_close_unknown_handle", LogCtx.NONE, *callFields(op.tag, op.handle))
            return EngineReply.Done
        }
        // Waits for a call still running on this handle: two binder threads never run one native engine at once.
        synchronized(r) { AppLog.span(TAG, "host_close", LogCtx.NONE, "kind" to r.kind.name, "backend" to r.backend, *callFields(op.tag, op.handle)) { r.close(op.tag) } }
        return EngineReply.Done
    }

    private fun status() = EngineReply.HostStatus(
        pid = Process.myPid(),
        uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
        pssKb = Debug.getPss(),
        handles = handles.entries.sortedBy { it.key }.map { (h, r) -> "$h:${r.kind.name.lowercase()}:${r.model}:${r.backend}" },
    )

    private companion object {
        const val TAG = "Engine"
    }
}
