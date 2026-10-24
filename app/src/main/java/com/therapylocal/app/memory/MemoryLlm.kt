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
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.NpuRuntime

/** Prefill and decode numbers LiteRT-LM reports for one call. [backend] is the one that really loaded, never a guess. */
data class LlmStats(
    val backend: String,
    val promptTokens: Int,
    val prefillTokPerSec: Double,
    val ttftSec: Double,
    val decodeTokens: Int,
    val decodeTokPerSec: Double,
    val wallMs: Long,
)

data class LlmReply(val text: String, val stats: LlmStats)

/** Text-only Gemma 4 E2B on LiteRT-LM for the memory pipeline. Every call is a fresh conversation, so nothing leaks between stages. */
@OptIn(ExperimentalApi::class)
class MemoryLlm(private val context: Context, private val maxTokens: Int) {
    var backend = "none"
        private set
    private var engine: Engine? = null

    /** [order] is tried in turn; each backend is validated with a small reply because GPU/NPU can fail only at the first inference. */
    fun load(order: List<String>) {
        ExperimentalFlags.enableBenchmark = true
        val file = ModelRegistry.GEMMA.file(context, ModelRegistry.GEMMA.files.first())
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
                val e = Engine(EngineConfig(modelPath = file.path, backend = be, maxNumTokens = maxTokens, cacheDir = context.cacheDir.path))
                e.initialize()
                e.createConversation().use { it.sendMessage("Reply with OK.") }
                engine = e
                backend = name.uppercase()
                AppLog.i(TAG, "memory_llm_loaded", LogCtx.NONE, "backend" to backend, "max_tokens" to maxTokens)
                return
            } catch (t: Throwable) {
                failures += "$name: ${t.message}"
                AppLog.w(TAG, "memory_llm_backend_failed", LogCtx.NONE, "backend_try" to name, "reason" to t.message)
            }
        }
        error("No backend could load the memory LLM. " + failures.joinToString("; "))
    }

    /** [schema] switches on constrained decoding for this call only (it looped on the large extraction schema, so callers use it for small schemas or as a last retry). */
    fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int = 1500, temperature: Double = 0.1, topK: Int = 20): LlmReply {
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
            val wall = (System.nanoTime() - t0) / 1_000_000
            return LlmReply(
                msg.toString(),
                LlmStats(backend, b.lastPrefillTokenCount, b.lastPrefillTokensPerSecond, b.timeToFirstTokenInSecond, b.lastDecodeTokenCount, b.lastDecodeTokensPerSecond, wall),
            )
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        const val TAG = "Memory"
    }
}
