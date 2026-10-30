package com.therapylocal.app.memory

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
import com.therapylocal.app.jobs.Asr
import com.therapylocal.app.jobs.Llm
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelSpec
import com.therapylocal.app.models.NpuRuntime

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

    /** [order] is tried in turn; each backend is validated with a small reply because GPU/NPU can fail only at the first inference. */
    fun load(order: List<String>) {
        ExperimentalFlags.enableBenchmark = true
        val file = spec.file(context, spec.files.first())
        // Audio-stripped builds exist: the bundle says whether it can hear (as the embedder checks too).
        audio = runCatching { ModelInfo.from(file.path).use { it.inputModalities().audio } }.getOrDefault(false)
        val failures = ArrayList<String>()
        for (name in order) {
            val be: Backend = when (name.uppercase()) {
                "NPU" -> {
                    if (!NpuRuntime.available(context)) {
                        AppLog.w(TAG, "npu_skipped", LogCtx.NONE, "reason" to "dispatch_library_missing")
                        failures += "NPU: dispatch library missing"
                        continue
                    }
                    Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                }
                "GPU" -> Backend.GPU()
                else -> Backend.CPU()
            }
            try {
                val config = EngineConfig(modelPath = file.path, backend = be, audioBackend = if (audio) Backend.CPU() else null, maxNumTokens = maxTokens, cacheDir = context.cacheDir.path)
                val e = Engine(config)
                e.initialize()
                e.createConversation().use { it.sendMessage("Reply with OK.") }
                engine = e
                backend = name.uppercase()
                AppLog.i(TAG, "memory_llm_loaded", LogCtx.NONE, "backend" to backend, "max_tokens" to maxTokens, "audio" to audio)
                return
            } catch (t: Throwable) {
                failures += "$name: ${t.message}"
                AppLog.w(TAG, "memory_llm_backend_failed", LogCtx.NONE, "backend_try" to name, "reason" to t.message)
            }
        }
        error("No backend could load the memory LLM. " + failures.joinToString("; "))
    }

    /** Speech recognition on this engine, or null if the model has no audio input. Closing it leaves the engine to this LLM. */
    fun speech(): Asr? = engine?.takeIf { audio }?.let { GemmaAsr(it, model, backend) }

    /** [schema] switches on constrained decoding for this call only (it looped on the large extraction schema, so callers use it for small schemas or as a last retry). */
    override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String {
        val e = checkNotNull(engine) { "not loaded" }
        val t0 = System.nanoTime()
        ExperimentalFlags.enableConversationConstrainedDecoding = schema != null
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
            return msg.toString()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        const val TAG = "Memory"
    }
}
