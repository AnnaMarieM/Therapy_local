package com.therapylocal.app.engine

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.therapylocal.app.engine.runner.AskEngine
import com.therapylocal.app.engine.runner.AsrEngine
import com.therapylocal.app.engine.runner.CanaryRunner
import com.therapylocal.app.engine.runner.EmbedEngine
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
        // Fault hooks (S11): no-ops in release, feel and qa. The model and backend of the handle let a rule name them.
        val r = handleOf(op)?.let { handles[it] }
        val model = (op as? EngineOp.Open)?.model ?: r?.model
        val backend = (op as? EngineOp.Open)?.backend ?: r?.backend
        Faults.at("before:${op.tag.op}", role, op, model, backend)
        val reply = dispatch(op, phases)
        Faults.at("after:${op.tag.op}", role, op, model, backend)
        return Faults.reply(role, op, reply)
    }

    private fun handleOf(op: EngineOp): Int? = when (op) {
        is EngineOp.Ask -> op.handle
        is EngineOp.EmbedText -> op.handle
        is EngineOp.EmbedAudio -> op.handle
        is EngineOp.Transcribe -> op.handle
        is EngineOp.Close -> op.handle
        else -> null
    }

    private fun dispatch(op: EngineOp, phases: PhaseSink): EngineReply {
        return when (op) {
            is EngineOp.Open -> open(op, phases)
            is EngineOp.Ask -> {
                val llm = runner<AskEngine>(op.handle, op.tag)
                synchronized(llm) { AppLog.spanRedacted(TAG, "host_ask", LogCtx.NONE, *callFields(op.tag, op.handle)) { llm.ask(op) } }
            }
            is EngineOp.EmbedText -> {
                val e = runner<EmbedEngine>(op.handle, op.tag)
                synchronized(e) { e.embedText(op) }
            }
            is EngineOp.EmbedAudio -> {
                val e = runner<EmbedEngine>(op.handle, op.tag)
                synchronized(e) { e.embedAudio(op) }
            }
            is EngineOp.Transcribe -> {
                val c = runner<AsrEngine>(op.handle, op.tag)
                synchronized(c) { c.transcribe(op) }
            }
            is EngineOp.Close -> close(op)
            is EngineOp.Status -> status()
            is EngineOp.ClearLogs -> {
                AppLog.clear()
                EngineReply.Done
            }
            is EngineOp.ArmFaults -> Faults.arm(op)
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
        Faults.at("before_open", role, op, op.model, op.backend)
        // The fault hook for "phase:<p>" fires after main has heard of the step and the host has logged and flushed it.
        val sink = PhaseSink { t, m, b, p ->
            phases.phase(t, m, b, p)
            Faults.at("phase:$p", role, op, op.model, op.backend)
        }
        val f = callFields(op.tag)
        AppLog.i(TAG, EngineEvents.HOST_OPEN, LogCtx.NONE, "model" to op.model, "backend_try" to op.backend, "kind" to op.kind.name, *f)
        return AppLog.span(TAG, "host_open", LogCtx.NONE, "model" to op.model, "backend_try" to op.backend, *f) { span ->
            // Each runner closes whatever it created if a step fails, so a failed attempt leaves nothing behind and no handle.
            // The fake-* model ids belong to the debug lab; the noFaults copy always answers null.
            val (runner, opened) = Faults.runner(op, role, sink) ?: when (op.kind) {
                EngineKind.LLM -> LlmRunner(context).let { it to it.open(op, sink) }
                EngineKind.EMBED -> EmbedRunner(context, role, op.textOnly).let { it to it.open(op, sink) }
                EngineKind.ASR -> CanaryRunner(context).let { it to it.open(op, sink) }
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

    /**
     * Process end: closes every handle (a failing close never stops the rest). Each close takes the runner's lock like [close] does, so it never
     * runs under a call still using the engine; a call that does not let go within [lockWaitMs] is left to the process ending.
     */
    fun closeAll(lockWaitMs: Long = CLOSE_ALL_LOCK_WAIT_MS) {
        for (h in handles.keys.toList()) {
            val r = handles.remove(h) ?: continue
            withMonitor(r, lockWaitMs) { runCatching { r.close(CallTag("shutdown", null, "close")) } }
        }
    }

    private fun status() = EngineReply.HostStatus(
        pid = Process.myPid(),
        uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
        pssKb = Debug.getPss(),
        handles = handles.entries.sortedBy { it.key }.map { (h, r) -> "$h:${r.kind.name.lowercase()}:${r.model}:${r.backend}" },
    )

    private companion object {
        const val TAG = "Engine"
        const val CLOSE_ALL_LOCK_WAIT_MS = 2_000L
    }
}

/**
 * Runs [block] holding [lock]'s monitor, as `synchronized(lock)` does, but gives up when the monitor is not free within [waitMs]: false then,
 * and [block] never runs (a late acquisition finds the attempt abandoned and lets go at once). Java has no timed monitor, so a helper thread
 * waits for it.
 */
internal fun withMonitor(lock: Any, waitMs: Long, block: () -> Unit): Boolean {
    val state = java.util.concurrent.atomic.AtomicInteger(0) // 0 waiting, 1 running, 2 abandoned
    val entered = java.util.concurrent.CountDownLatch(1)
    val done = java.util.concurrent.CountDownLatch(1)
    Thread({
        synchronized(lock) {
            if (state.compareAndSet(0, 1)) {
                entered.countDown()
                try { block() } finally { done.countDown() }
            }
        }
    }, "engine-close-all").apply { isDaemon = true }.start()
    if (!entered.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) && state.compareAndSet(0, 2)) return false
    done.await()
    return true
}
