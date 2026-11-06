package com.therapylocal.app.memory

import com.therapylocal.app.perf.LlmCall
import com.therapylocal.app.perf.PerfStats
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.therapylocal.app.asr.GemmaAsr
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.jobs.Asr
import com.therapylocal.app.jobs.Llm
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelSpec
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.LiteRtBindings
import com.therapylocal.app.models.ModelRuntime

/**
 * Gemma 4 ([spec]: the E2B or E4B build) on LiteRT-LM for the memory pipeline. Every call is a fresh conversation, so nothing leaks between stages. When the model bundle
 * has an audio encoder, the same engine also transcribes speech ([speech]), so Gemma is never loaded twice.
 */
@OptIn(ExperimentalApi::class)
class MemoryLlm(private val context: Context, private val maxTokens: Int, private val spec: ModelSpec) : Llm {
    override val model: String = spec.id

    var backend = "none"
        private set
    private var engine: Engine? = null
    private var audio = false

    /** The model as [Backends.guard] keeps it: the audio build loads more, so it is proven separately. */
    private var guardModel = spec.id

    override val hasAudio: Boolean get() = audio

    override val contextTokens: Int get() = maxTokens

    /**
     * [order] (by default every [Accelerators] entry that serves this model on LiteRT-LM) is tried in turn, through [BackendPolicy.order] so
     * the debug override goes first and the CPU stays the fallback; each backend is
     * validated with a small reply because GPU/NPU can fail only at the first inference. Every attempt and its outcome is kept per device by
     * [Backends.guard]: a backend that keeps failing or killing the app here is skipped from then on.
     * Only the text model is loaded unless [withAudio] (speech recognition): the audio encoder and the image encoder cost memory for nothing in
     * the text jobs. [respectBlocks] false (QA probes) tries a blocked backend anyway.
     */
    fun load(order: List<String>? = null, withAudio: Boolean = false, respectBlocks: Boolean = true) {
        ExperimentalFlags.enableBenchmark = true
        val file = spec.file(context, spec.files.first())
        // Audio-stripped builds exist: the bundle says whether it can hear (as the embedder checks too).
        audio = withAudio && runCatching { ModelInfo.from(file.path).use { it.inputModalities().audio } }.getOrDefault(false)
        guardModel = if (audio) "${spec.id}+audio" else spec.id
        val preferred = order ?: Accelerators.order(spec.id, ModelRuntime.LITERT)
        AppLog.i(
            TAG, "memory_llm_load_request", LogCtx.NONE, "model" to spec.id, "guard_model" to guardModel, "file_mb" to (file.length() shr 20),
            "file_exists" to file.exists(), "max_tokens" to maxTokens, "with_audio" to withAudio, "audio" to audio, "order" to preferred.joinToString(","), "order_from" to if (order == null) "accelerators" else "caller",
            "override" to BackendPolicy.override, "respect_blocks" to respectBlocks,
        )
        val loaded = Backends.ladder(context, TAG, "memory_llm").load(
            model = guardModel,
            candidates = BackendPolicy.order(preferred),
            respectBlocks = respectBlocks,
            gate = { name -> gate(name) },
            open = { name -> open(file.path, name) },
            close = { it.close() },
        )
        engine = loaded.engine
        backend = loaded.backend
        AppLog.i(TAG, "memory_llm_loaded", LogCtx.NONE, "load_id" to loaded.loadId, "backend" to backend, "max_tokens" to maxTokens, "audio" to audio, "skipped" to loaded.skipped.joinToString("; "))
        AppLog.logMemory(context, TAG, "memory_llm_ready", extra = arrayOf("load_id" to loaded.loadId, "backend" to backend, "model" to spec.id))
    }

    /** Why [name] is not tried now, or null. Accelerators that take the model's memory from the system (GPU, NPU) need it free. */
    private fun gate(name: String): String? {
        Accelerators.unavailable(context, name, spec.id, ModelRuntime.LITERT)?.let { return it }
        if (Accelerators.get(name)?.takesSystemMemory != true) return null
        val (available, low) = LlmMemory.available(context)
        val needed = LlmMemory.gpuNeeds(spec.totalBytes)
        AppLog.d(TAG, "backend_memory_gate", LogCtx.NONE, "backend_try" to name, "available_mb" to (available shr 20), "needed_mb" to (needed shr 20), "low_memory" to low, "model_mb" to (spec.totalBytes shr 20))
        if (!LlmMemory.gpuFits(spec.totalBytes, available, low)) {
            return "not enough free memory (${available shr 20} MB free, ${needed shr 20} MB needed${if (low) ", system reports low memory" else ""})"
        }
        return null
    }

    /** Creates, initialises and test-runs an engine on [name]; one that fails on the way is closed here, so its memory is free for the next try. */
    private fun open(path: String, name: String): Engine {
        val be: Backend = LiteRtBindings.backend(context, name)
        val config = EngineConfig(modelPath = path, backend = be, audioBackend = if (audio) Backend.CPU() else null, visionBackend = null, maxNumTokens = maxTokens, cacheDir = context.cacheDir.path)
        // Each phase on its own line: a native kill leaves the last phase that started as the final line in the log.
        val e = AppLog.span(TAG, "memory_llm_engine_create", LogCtx.NONE, "backend_try" to name) { Engine(config) }
        try {
            AppLog.span(TAG, "memory_llm_engine_initialize", LogCtx.NONE, "backend_try" to name, "max_tokens" to maxTokens, "audio" to audio) { e.initialize() }
            AppLog.span(TAG, "memory_llm_engine_test_reply", LogCtx.NONE, "backend_try" to name) { span ->
                val reply = e.createConversation().use { it.sendMessage("Reply with OK.") }.toString()
                span.put("reply_chars", reply.length)
            }
            return e
        } catch (t: Throwable) {
            runCatching { e.close() }.onFailure { AppLog.w(TAG, "memory_llm_engine_close_after_failure_failed", LogCtx.NONE, "backend_try" to name, "reason" to it.message) }
            throw t
        }
    }

    /** Speech recognition on this engine, or null if the model has no audio input. Closing it leaves the engine to this LLM. */
    fun speech(): Asr? = engine?.takeIf { audio }?.let { GemmaAsr(it, model, backend) }

    /** [schema] switches on constrained decoding for this call only (it looped on the large extraction schema, so callers use it for small schemas or as a last retry). */
    override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String {
        val e = checkNotNull(engine) { "not loaded" }
        val t0 = System.nanoTime()
        // A global flag: switched off again afterwards, or every later conversation on the engine (speech recognition too) pays its ~4 s setup.
        ExperimentalFlags.enableConversationConstrainedDecoding = schema != null
        try {
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = topK, topP = 0.9, temperature = temperature, seed = 1),
                enableResponseFormat = schema != null,
                maxOutputToken = maxOutputTokens,
            )
            e.createConversation(cfg).use { c ->
                val msg = if (schema != null) c.sendMessage(user, responseFormat = ResponseFormat.json(schema)) else c.sendMessage(user)
                val b: BenchmarkInfo = c.getBenchmarkInfo()
                // Numbers only, never text.
                AppLog.i(
                    TAG, "llm_call", LogCtx.NONE, "backend" to backend, "constrained" to (schema != null), "prompt_tokens" to b.lastPrefillTokenCount,
                    "prefill_tok_s" to b.lastPrefillTokensPerSecond, "ttft_s" to b.timeToFirstTokenInSecond, "decode_tokens" to b.lastDecodeTokenCount,
                    "decode_tok_s" to b.lastDecodeTokensPerSecond, "wall_ms" to (System.nanoTime() - t0) / 1_000_000,
                )
                PerfStats.call(
                    spec.id,
                    LlmCall(
                        b.lastPrefillTokenCount.toInt(), b.lastPrefillTokensPerSecond.toDouble(), b.lastDecodeTokenCount.toInt(),
                        b.lastDecodeTokensPerSecond.toDouble(), (System.nanoTime() - t0) / 1_000_000,
                    ),
                )
                return msg.toString()
            }
        } catch (t: Throwable) {
            // Noted per device, so a GPU/NPU that loads but then fails on real prompts shows up in the backend records. Types and code
            // locations only, never the message: an inference error can quote the prompt, which is patient text.
            val r = runCatching { Backends.guard(context).inferenceFailed(guardModel, backend, t.javaClass.simpleName) }.getOrNull()
            AppLog.e(
                TAG, "llm_call_failed", LogCtx.NONE, null, "backend" to backend, "model" to guardModel, "constrained" to (schema != null),
                "system_chars" to system.length, "user_chars" to user.length, "max_output_tokens" to maxOutputTokens, "context_tokens" to maxTokens,
                "wall_ms" to (System.nanoTime() - t0) / 1_000_000, "error" to t.javaClass.name, "cause_chain" to BackendLadder.causeChain(t, messages = false),
                "frames" to BackendLadder.frames(t), "is_oom" to (t is OutOfMemoryError), "record_state" to r?.state?.name?.lowercase(),
                *com.therapylocal.app.memory.DeviceMemory.memorySnapshot(context), *Backends.thermal(context),
            )
            throw t
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override fun close() {
        val e = engine ?: return
        engine = null
        val t0 = System.nanoTime()
        runCatching { e.close() }.onFailure { AppLog.e(TAG, "memory_llm_close_failed", LogCtx.NONE, it, "backend" to backend) }
        runCatching { Backends.guard(context).unloaded(guardModel, backend) }
        AppLog.i(TAG, "memory_llm_unloaded", LogCtx.NONE, "model" to guardModel, "backend" to backend, "close_ms" to (System.nanoTime() - t0) / 1_000_000)
        AppLog.logMemory(context, TAG, "memory_llm_after_unload", extra = arrayOf("backend" to backend, "model" to guardModel))
        backend = "none"
    }

    companion object {
        const val TAG = "Memory"
    }
}
