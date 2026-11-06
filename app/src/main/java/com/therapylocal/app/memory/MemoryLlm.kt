package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.engine.ReplyTooLarge
import com.therapylocal.app.jobs.JobFailure
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.guardRole
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineProxy
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.jobs.Llm
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelRuntime
import com.therapylocal.app.models.ModelSpec
import com.therapylocal.app.perf.PerfStats

/**
 * Gemma 4 ([spec]: the E2B or E4B build) for the memory pipeline, text only (docs/AD/AD-02.md). Every call is a fresh conversation, so
 * nothing leaks between stages. The native engine lives in an [EngineHost]; this class keeps what must stay in main: the backend ladder
 * with its guard, the memory gate, [PerfStats] and the health of the backend. It imports nothing native.
 *
 * [ladder], [guard] and [gate] are test seams; production uses the defaults.
 */
class MemoryLlm(
    private val context: Context,
    private val maxTokens: Int,
    private val spec: ModelSpec,
    host: EngineHost = Engines.batchHost(context),
    private val ladder: () -> BackendLadder = { Backends.ladder(context, TAG, "memory_llm", host.guardRole) },
    private val guard: () -> BackendGuard? = { Backends.guard(context) },
    private val gate: (String) -> String? = { name -> memoryGate(context, spec, name) },
    private val proxy: EngineProxy = EngineProxy(context, { host }, TAG, "memory_llm"),
) : Llm {
    override val model: String = spec.id

    var backend = "none"
        private set
    private var handle: Int? = null

    override val contextTokens: Int get() = maxTokens

    /** Turned false when the backend failed on [BackendGuard.INFERENCE_STRIKE] prompts in a row: the pool then reloads, on the next backend. */
    @Volatile
    override var healthy: Boolean = true
        private set

    /**
     * [order] (by default every [Accelerators] entry that serves this model on LiteRT-LM) is tried in turn, through [BackendPolicy.order] so
     * the debug override goes first and the CPU stays the fallback; each backend is validated with a small reply because GPU/NPU can fail
     * only at the first inference. Every attempt and its outcome is kept per device by [Backends.guard]: a backend that keeps failing or
     * killing the app here is skipped from then on. Only the text model is loaded. [respectBlocks] false (QA probes) tries a blocked backend anyway.
     */
    fun load(order: List<String>? = null, respectBlocks: Boolean = true) {
        val preferred = order ?: Accelerators.order(spec.id, ModelRuntime.LITERT)
        val fileMb = runCatching { val f = spec.file(context, spec.files.first()); f.length() shr 20 to f.exists() }.getOrNull()
        AppLog.i(
            TAG, "memory_llm_load_request", LogCtx.NONE, "model" to spec.id, "file_mb" to fileMb?.first, "file_exists" to fileMb?.second, "max_tokens" to maxTokens,
            "order" to preferred.joinToString(","), "order_from" to if (order == null) "accelerators" else "caller",
            "override" to BackendPolicy.override, "respect_blocks" to respectBlocks,
        )
        val loadId = BackendLadder.newLoadId()
        // A model the system kept killing even on the CPU cannot run here: fail the job for good (a retry would be killed again), unless a
        // person forces the load (debug override, QA probe).
        val judged = respectBlocks && !BackendPolicy.forcing()
        if (judged && runCatching { guard()?.tooLarge(spec.id) }.getOrNull() == true) {
            AppLog.w(TAG, "model_too_large_for_device", LogCtx.NONE, "model" to spec.id, "load_id" to loadId, "when" to "before_load")
            throw JobFailure.modelTooLarge()
        }
        // The model before this one (Canary, the embedder) may still be closing: the memory gate must see its memory freed and the host must
        // not hold two models. Bounded, so a stuck close only delays the load.
        proxy.awaitPendingCloses()
        val loaded = try {
            ladder().load(
                loadId = loadId,
                model = spec.id,
                candidates = BackendPolicy.order(preferred),
                respectBlocks = judged,
                gate = gate,
                open = { name -> proxy.open(EngineKind.LLM, spec.id, spec.id, name, loadId = loadId, maxTokens = maxTokens, textOnly = true) },
                close = { runCatching { proxy.closeNow(it.handle) } },
            )
        } catch (e: BackendLadder.NoBackend) {
            // Every backend failed, and the CPU was killed for memory again and again: say so, not "no backend".
            if (judged && runCatching { guard()?.tooLarge(spec.id) }.getOrNull() == true) {
                AppLog.w(TAG, "model_too_large_for_device", LogCtx.NONE, "model" to spec.id, "load_id" to loadId, "when" to "after_ladder")
                throw JobFailure.modelTooLarge()
            }
            throw e
        }
        handle = loaded.engine.handle
        backend = loaded.backend
        healthy = true
        // Only logging from here on: nothing may throw past the point where the engine is ours, or it would never be closed.
        runCatching {
            val pss = proxy.statusIfBound()?.pssKb
            AppLog.i(TAG, "memory_llm_loaded", LogCtx.NONE, "load_id" to loaded.loadId, "backend" to backend, "max_tokens" to maxTokens, "skipped" to loaded.skipped.joinToString("; "))
            AppLog.logMemory(context, TAG, "memory_llm_ready", extra = arrayOf("load_id" to loaded.loadId, "backend" to backend, "model" to spec.id, "host_pss_kb" to pss))
        }
    }

    /** [schema] switches on constrained decoding for this call only (it looped on the large extraction schema, so callers use it for small schemas or as a last retry). */
    override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String {
        val h = checkNotNull(handle) { "not loaded" }
        val t0 = System.nanoTime()
        try {
            val reply = proxy.call<EngineReply.Text>(EngineOp.Ask(proxy.newTag("ask", null), h, system, user, schema, maxOutputTokens, temperature, topK))
            reply.perf?.let { PerfStats.call(spec.id, it) }
            runCatching { guard()?.inferenceOk(spec.id, backend) }
            return reply.text
        } catch (t: Throwable) {
            // Noted per device; failing on prompts again and again strikes the backend and marks this engine unhealthy, so the pool reloads
            // it on the next backend. Types and code locations only, never the message: an inference error can quote the prompt.
            // A host that died or could not be reached is not a bad prompt: the connection already struck what was at fault.
            val notThePrompt = t is EngineDied || t is EngineUnavailable || t is ReplyTooLarge
            val r = if (notThePrompt) null else runCatching { guard()?.inferenceFailed(spec.id, backend, BackendLadder.name(t)) }.getOrNull()
            // The host ended under this handle (a death, or an idle stop): the handle is gone, so the pool must reload.
            if (r != null || t is EngineDied) healthy = false
            // The connection's registry already settled this handle in the guard; the pool's close() must not settle it again (unloaded would reset strikes).
            if (t is EngineDied) handle = null
            runCatching {
                AppLog.eRedacted(
                    TAG, "llm_call_failed", LogCtx.NONE, t, "backend" to backend, "model" to spec.id, "constrained" to (schema != null),
                    "system_chars" to system.length, "user_chars" to user.length, "max_output_tokens" to maxOutputTokens, "context_tokens" to maxTokens,
                    "wall_ms" to (System.nanoTime() - t0) / 1_000_000, "demoted" to (r != null), "record_state" to r?.state?.name?.lowercase(),
                    "record_strikes" to r?.strikes, *runCatching { DeviceMemory.memorySnapshot(context) }.getOrDefault(emptyArray()),
                    *runCatching { Backends.thermal(context) }.getOrDefault(emptyArray()),
                )
            }
            throw t
        }
    }

    override fun close() {
        val h = handle ?: return
        handle = null
        val t0 = System.nanoTime()
        val b = backend
        // The guard hears `unloaded` once the host acknowledged the close, not when it was only asked: a death in between is a death.
        proxy.close(h) { acked -> if (acked) runCatching { guard()?.unloaded(spec.id, b) } }
        AppLog.i(TAG, "memory_llm_unloaded", LogCtx.NONE, "model" to spec.id, "backend" to backend, "close_ms" to (System.nanoTime() - t0) / 1_000_000)
        runCatching {
            AppLog.logMemory(context, TAG, "memory_llm_after_unload", extra = arrayOf("backend" to backend, "model" to spec.id, "host_pss_kb" to proxy.statusIfBound()?.pssKb))
        }
        backend = "none"
    }

    companion object {
        const val TAG = "Memory"

        /** Why [name] is not tried now, or null. Accelerators that take the model's memory from the system (GPU, NPU) need it free. The check is system-wide, so main is right. */
        internal fun memoryGate(context: Context, spec: ModelSpec, name: String): String? =
            com.therapylocal.app.engine.LabWiring.gate(realMemoryGate(context, spec, name))

        private fun realMemoryGate(context: Context, spec: ModelSpec, name: String): String? {
            Accelerators.unavailable(context, name, spec.id, ModelRuntime.LITERT)?.let { return it }
            if (BackendPolicy.forcing()) { AppLog.w(TAG, "backend_memory_gate_forced", LogCtx.NONE, "backend_try" to name); return null }
            if (Accelerators.get(name)?.takesSystemMemory != true) return null
            val (available, low) = LlmMemory.available(context)
            val needed = LlmMemory.gpuNeeds(spec.totalBytes)
            AppLog.d(TAG, "backend_memory_gate", LogCtx.NONE, "backend_try" to name, "available_mb" to (available shr 20), "needed_mb" to (needed shr 20), "low_memory" to low, "model_mb" to (spec.totalBytes shr 20))
            if (!LlmMemory.gpuFits(spec.totalBytes, available, low)) {
                return "not enough free memory (${available shr 20} MB free, ${needed shr 20} MB needed${if (low) ", system reports low memory" else ""})"
            }
            return null
        }
    }
}
