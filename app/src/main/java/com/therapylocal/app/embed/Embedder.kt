package com.therapylocal.app.embed

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.NpuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 (740M, natively multimodal) on LiteRT-LM. One engine embeds text and audio into the same 768-d space, so
 * a chunk's AUDIO is embedded directly (no transcript needed) and a text or voice query is compared against it.
 *
 * Adapted from working code, with sources:
 *  - LiteRT-LM Kotlin API: https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt
 *    and EmbeddingConfig.kt in the same folder (EmbeddingEngineConfig, EmbeddingOptions, InputData.Audio / InputData.Text).
 *  - Google AI Edge Gallery, OnDeviceEmbedder.kt and EmbedPart.kt (Apache-2.0):
 *    https://github.com/google-ai-edge/gallery/tree/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval
 *    taken from there: audio is 16 kHz mono; the audio encoder is forced to CPU "for performance and accuracy purposes";
 *    audio support is read from ModelInfo.inputModalities() because audio-stripped builds exist; NPU needs the dispatch
 *    library in the native library dir; the LiteRT-LM cache dir is isolated per signature config.
 *  - Task prefixes: https://developers.google.com/edge/litert-lm/embedding_models and the model card
 *    https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm
 *
 * Backend order for the text encoder: NPU (only when the Qualcomm dispatch library is present, see [NpuRuntime]), GPU, CPU.
 */
class Embedder(private val context: Context, private val forcedBackend: String? = null) {
    var backend: String = "none"
        private set
    val dim = EmbeddingConfig.DIM

    /** [EmbeddingConfig.MAX_TOKENS], lowered to the context the model reports. Known after [load]. */
    var maxTokens: Int = EmbeddingConfig.MAX_TOKENS
        private set

    private var engine: EmbeddingEngine? = null
    private val lock = Mutex()

    suspend fun load() = lock.withLock {
        if (engine != null) return@withLock
        withContext(Dispatchers.Default) {
            val file = ModelRegistry.EMBED.file(context, ModelRegistry.EMBED.files.first())
            AppLog.span(TAG, "embed_load", LogCtx.NONE, "model" to EmbeddingConfig.MODEL_ID, "model_mb" to (file.length() shr 20), "dim" to dim) { span ->
                maxTokens = logModelInfo(file)
                span.put("max_tokens", maxTokens)
                val attempts = ArrayList<Pair<String, () -> Backend>>()
                if (forcedBackend != null) {
                    when (forcedBackend.uppercase()) {
                        "CPU" -> attempts += "CPU" to { Backend.CPU() }
                        "GPU" -> attempts += "GPU" to { Backend.GPU() }
                        "NPU" -> attempts += "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) }
                        else -> attempts += "CPU" to { Backend.CPU() }
                    }
                } else {
                    if (BackendPolicy.allows("NPU")) {
                        if (NpuRuntime.available(context) || BackendPolicy.override == "npu") {
                            attempts += "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) }
                        } else {
                            AppLog.w(TAG, "npu_skipped", LogCtx.NONE, "reason" to "dispatch_library_missing", "expected" to NpuRuntime.DISPATCH_LIB, "dir" to context.applicationInfo.nativeLibraryDir)
                        }
                    }
                    if (BackendPolicy.allows("GPU")) attempts += "GPU" to { Backend.GPU() }
                    if (BackendPolicy.allows("CPU")) attempts += "CPU" to { Backend.CPU() }
                }

                val failures = ArrayList<String>()
                for ((name, make) in attempts) {
                    try {
                        val e = AppLog.span(TAG, "embed_engine_init", LogCtx.NONE, "backend_try" to name, "audio_backend" to "CPU") {
                            val created = EmbeddingEngine(
                                EmbeddingEngineConfig(
                                    modelPath = file.path,
                                    backend = make(),
                                    audioBackend = Backend.CPU(), // Gallery pins the audio encoder to CPU
                                    cacheDir = isolatedCacheDir().path,
                                    maxInputLength = maxTokens,
                                ),
                            )
                            created.initialize()
                            created
                        }
                        engine = e
                        selfTest(e, name)
                        backend = name
                        span.put("backend", name)
                        span.put("audio_backend", "CPU")
                        span.put("fallbacks_failed", failures.size)
                        AppLog.i(TAG, "backend_selected", LogCtx.NONE, "engine" to "embeddinggemma", "backend" to name, "audio_backend" to "CPU", "model_file" to file.name, "model_bytes" to file.length())
                        return@span
                    } catch (t: Throwable) {
                        runCatching { engine?.close() }
                        engine = null
                        failures += "$name: ${t.message}"
                        AppLog.w(TAG, "backend_failed", LogCtx.NONE, "engine" to "embeddinggemma", "backend_try" to name, "reason" to t.message)
                    }
                }
                error("No backend could load EmbeddingGemma. " + failures.joinToString("; "))
            }
        }
    }

    /** What the model bundle itself declares: modalities, backends, NPU brand. Audio support is required. Returns the input length to use. */
    private fun logModelInfo(file: File): Int =
        ModelInfo.from(file.path).use { info ->
            val m = info.inputModalities()
            AppLog.i(
                TAG, "model_info", LogCtx.NONE,
                "text" to m.text, "audio" to m.audio, "vision" to m.vision, "max_context_tokens" to info.maxContextTokens(),
                "backends_text" to info.supportedBackends(Modality.TEXT).joinToString("/") { it.name },
                "backends_audio" to info.supportedBackends(Modality.AUDIO).joinToString("/") { it.name },
                "npu_brand" to info.npuBrand(Modality.TEXT).name, "bundle_soc" to info.socName(Modality.TEXT),
            )
            check(m.audio) { "This EmbeddingGemma build has no audio encoder" }
            val reported = info.maxContextTokens()
            if (reported in 1 until EmbeddingConfig.MAX_TOKENS) reported else EmbeddingConfig.MAX_TOKENS
        }

    private fun isolatedCacheDir(): File = File(context.cacheDir, "emb_t$maxTokens").apply { mkdirs() }

    /** Embeds the AUDIO of one chunk directly. No transcript is involved. */
    suspend fun embedAudio(pcm: ShortArray, ctx: LogCtx, role: String = "document"): FloatArray = lock.withLock {
        val e = checkNotNull(engine) { "Embedder not loaded" }
        withContext(Dispatchers.Default) { runAudio(e, pcm, ctx, role) }
    }

    /** Embeds text as a query ("task: search query | text: ...") or as a document. */
    suspend fun embedText(text: String, query: Boolean, ctx: LogCtx): FloatArray = lock.withLock {
        val e = checkNotNull(engine) { "Embedder not loaded" }
        withContext(Dispatchers.Default) { runText(e, text, query, ctx) }
    }

    private fun runAudio(e: EmbeddingEngine, pcm: ShortArray, ctx: LogCtx, role: String): FloatArray {
        val audioSec = Pcm.seconds(pcm)
        val wav = AppLog.span(TAG, "audio_wav_encode", ctx, "samples" to pcm.size, "audio_sec" to "%.2f".format(audioSec)) { span ->
            Pcm.toWav(pcm).also { span.put("wav_bytes", it.size) }
        }
        return AppLog.span(TAG, "embed", ctx, "modality" to "audio", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "audio_backend" to "CPU", "role" to role, "audio_sec" to "%.2f".format(audioSec)) { span ->
            val t0 = System.nanoTime()
            val v = e.computeEmbedding(listOf(InputData.Audio(wav)), options()).embedding
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            span.put("rtf", "%.3f".format(ms / 1000.0 / audioSec))
            finish(v, span)
        }
    }

    private fun runText(e: EmbeddingEngine, text: String, query: Boolean, ctx: LogCtx): FloatArray =
        AppLog.span(TAG, "embed", ctx, "modality" to "text", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "role" to if (query) "query" else "document", "text_chars" to text.length) { span ->
            val prefixed = (if (query) EmbeddingConfig.QUERY_PREFIX else EmbeddingConfig.DOC_PREFIX) + text.trim()
            finish(e.computeEmbedding(listOf(InputData.Text(prefixed)), options()).embedding, span)
        }

    private fun options() = EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = EmbeddingConfig.DIM)

    private fun finish(v: FloatArray, span: com.therapylocal.app.log.Span): FloatArray {
        val norm = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()
        span.put("dim", v.size)
        span.put("norm", norm)
        // Defensive re-normalise so cosine == dot product whatever the runtime did.
        return if (norm > 0f && kotlin.math.abs(norm - 1f) > 1e-3f) FloatArray(v.size) { v[it] / norm } else v
    }

    /** Text pair similarity plus an audio smoke test (a 1 s tone). Proves both encoders run and the text space is semantic. */
    private fun selfTest(e: EmbeddingEngine, backendName: String) {
        AppLog.span(TAG, "embed_selftest", LogCtx.NONE, "backend_try" to backendName) { span ->
            val q = runText(e, "my boss keeps criticising my work", true, LogCtx.NONE)
            val related = runText(e, "my manager is always unhappy with my reports", false, LogCtx.NONE)
            val unrelated = runText(e, "we went hiking in the mountains last weekend", false, LogCtx.NONE)
            span.put("sim_related", dot(q, related))
            span.put("sim_unrelated", dot(q, unrelated))
            check(q.size == dim) { "unexpected embedding size ${q.size}" }
            check(dot(q, related) > dot(q, unrelated)) { "text self-test failed" }
            val tone = ShortArray(Pcm.SAMPLE_RATE) { (8000 * sin(2 * Math.PI * 440 * it / Pcm.SAMPLE_RATE)).toInt().toShort() }
            val a = runAudio(e, tone, LogCtx.NONE, "selftest")
            span.put("audio_dim", a.size)
            check(a.size == dim) { "unexpected audio embedding size ${a.size}" }
        }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
        backend = "none"
    }

    companion object {
        const val TAG = "Embed"

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
