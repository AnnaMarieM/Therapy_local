package com.therapylocal.app.embed

import android.content.Context
import android.os.SystemClock
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
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.LlmMemory
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.LiteRtBindings
import com.therapylocal.app.models.ModelRuntime
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
 * Backend order for the text encoder: the [Accelerators] that serve it on LiteRT-LM (NPU only with the Qualcomm dispatch library, then GPU, CPU).
 */
class Embedder(
    private val context: Context,
    private val forcedBackend: String? = null,
    /** Load only the text encoder: the audio encoder is left out (the pipeline embeds text only). [embedAudio] then fails. */
    private val textOnly: Boolean = false,
) {
    var backend: String = "none"
        private set
    val dim = EmbeddingConfig.DIM

    /** [EmbeddingConfig.MAX_TOKENS], lowered to the context the model reports. Known after [load]. */
    var maxTokens: Int = EmbeddingConfig.MAX_TOKENS
        private set

    /** Longest audio clip one call can take, from [maxTokens]. */
    val maxAudioSeconds get() = EmbeddingConfig.maxAudioSeconds(maxTokens)

    private var engine: EmbeddingEngine? = null
    private val lock = Mutex()

    /** [close] was asked for while a call held the lock: that call closes the engine when it is done. */
    @Volatile
    private var closeRequested = false

    /** Until this [SystemClock.elapsedRealtime], loads skip GPU and NPU (see [releaseForMemory]). */
    @Volatile
    private var cpuOnlyUntil = 0L

    /**
     * True while the embedder is held back so it does not sit in memory beside the LLM (see [Engines.suspend]). An embedder that is not
     * suspended but has been closed reloads itself on first use.
     */
    @Volatile
    var suspended = false
        private set

    /** Whether [embedText] and [embedAudio] can run now. */
    val usable: Boolean get() = !suspended

    /**
     * A [close] asked for during the load (a settings change, the system asking for memory) is carried out as soon as it is done. While
     * [suspended] nothing is loaded: the session LLM may hold the GPU, and both on it at once is what gets the app killed. It loads on first
     * use after [resume].
     */
    suspend fun load() = lock.withLock {
        if (suspended) {
            AppLog.i(TAG, "embed_load_deferred", LogCtx.NONE, "reason" to "suspended_while_sessions_are_processed")
            return@withLock
        }
        try {
            loadLocked()
        } finally {
            closeIfRequested()
        }
    }

    private suspend fun loadLocked() {
        if (engine != null) return
        withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) {
            val file = ModelRegistry.EMBED.file(context, ModelRegistry.EMBED.files.first())
            AppLog.span(TAG, "embed_load", LogCtx.NONE, "model" to EmbeddingConfig.MODEL_ID, "model_mb" to (file.length() shr 20), "dim" to dim) { span ->
                maxTokens = logModelInfo(file)
                span.put("max_tokens", maxTokens)
                val preferred = Accelerators.order(EmbeddingConfig.MODEL_ID, ModelRuntime.LITERT)
                val candidates = if (forcedBackend != null) BackendPolicy.order(listOf(Accelerators.fallback.id), forcedBackend) else BackendPolicy.order(preferred)
                val forced = forcedBackend != null || !BackendPolicy.override.equals("auto", ignoreCase = true)
                AppLog.i(
                    TAG, "embed_load_request", LogCtx.NONE, "guard_model" to guardModel, "text_only" to textOnly, "forced_backend" to forcedBackend,
                    "override" to BackendPolicy.override, "candidates" to candidates.joinToString(","), "max_tokens" to maxTokens,
                    "cpu_only_for_ms" to (cpuOnlyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                )
                val loaded = Backends.ladder(context, TAG, "embeddinggemma").load(
                    model = guardModel,
                    candidates = candidates,
                    gate = { name -> gate(name, forced) },
                    open = { name -> open(file, name) },
                    close = { runCatching { it.close() } },
                )
                engine = loaded.engine
                backend = loaded.backend
                // Only logging from here on: nothing may throw past the point where the engine is ours.
                runCatching {
                    span.put("load_id", loaded.loadId)
                    span.put("backend", backend)
                    span.put("audio_backend", audioBackendName())
                    span.put("fallbacks_failed", loaded.skipped.size)
                    AppLog.i(TAG, "backend_selected", LogCtx.NONE, "engine" to "embeddinggemma", "load_id" to loaded.loadId, "backend" to backend, "audio_backend" to audioBackendName(), "model_file" to file.name, "model_bytes" to file.length(), "skipped" to loaded.skipped.joinToString("; "))
                    AppLog.logMemory(context, TAG, "embed_ready", extra = arrayOf("backend" to backend))
                }
            }
        }
    }

    /** The model as [Backends.guard] keeps it: with the audio encoder it loads more, so it is proven separately. */
    private val guardModel get() = if (textOnly) EmbeddingConfig.MODEL_ID else "${EmbeddingConfig.MODEL_ID}+audio"

    /**
     * Why [name] is not tried now, or null. A [forced] backend (debug override) skips the device-class rule, never availability or the
     * free-memory check: GPU and NPU take their memory from the system, and running out there gets the app killed, not an exception.
     */
    private fun gate(name: String, forced: Boolean): String? {
        Accelerators.unavailable(context, name, EmbeddingConfig.MODEL_ID, ModelRuntime.LITERT)?.let { return it }
        if (Accelerators.get(name)?.takesSystemMemory != true) return null
        if (SystemClock.elapsedRealtime() < cpuOnlyUntil) return "CPU only for a while after the system asked for memory"
        if (Accelerators.get(name)?.graphicsMemory == true && !forced && !com.therapylocal.app.memory.DeviceMemory.allowEmbedderGpu(context)) return "memory safety: Tensor/Mali GPU or not enough RAM"
        val (available, low) = LlmMemory.available(context)
        val bytes = ModelRegistry.EMBED.totalBytes
        if (!LlmMemory.gpuFits(bytes, available, low)) return "not enough free memory (${available shr 20} MB free, ${LlmMemory.gpuNeeds(bytes) shr 20} MB needed)"
        return null
    }

    /** Creates, initialises and self-tests an engine on [name]; one that fails on the way is closed here, so its memory is free for the next try. */
    private fun open(file: File, name: String): EmbeddingEngine {
        // Each native step is noted and flushed before it runs: after a kill, the log and the next start both name the step that caused it.
        Backends.phase(context, TAG, guardModel, name, "create")
        val created = AppLog.span(TAG, "embed_engine_init", LogCtx.NONE, "backend_try" to name, "audio_backend" to audioBackendName()) {
            EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = file.path,
                    backend = LiteRtBindings.backend(context, name),
                    audioBackend = if (textOnly) null else Backend.CPU(), // Gallery pins the audio encoder to CPU
                    cacheDir = isolatedCacheDir().path,
                    maxInputLength = maxTokens,
                ),
            )
        }
        try {
            Backends.phase(context, TAG, guardModel, name, "initialize")
            AppLog.span(TAG, "embed_engine_initialize", LogCtx.NONE, "backend_try" to name) { created.initialize() }
            Backends.phase(context, TAG, guardModel, name, "test")
            selfTest(created, name)
            return created
        } catch (t: Throwable) {
            runCatching { created.close() }.onFailure { AppLog.w(TAG, "embed_engine_close_after_failure_failed", LogCtx.NONE, "backend_try" to name, "reason" to it.message) }
            throw t
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
            check(textOnly || m.audio) { "This EmbeddingGemma build has no audio encoder" }
            val reported = info.maxContextTokens()
            if (reported in 1 until EmbeddingConfig.MAX_TOKENS) reported else EmbeddingConfig.MAX_TOKENS
        }

    private fun isolatedCacheDir(): File = File(context.cacheDir, "emb_t$maxTokens").apply { mkdirs() }

    private fun audioBackendName() = if (textOnly) "none" else "CPU"

    /** Embeds the AUDIO of one chunk directly. No transcript is involved. */
    suspend fun embedAudio(pcm: ShortArray, ctx: LogCtx, role: String = "document"): FloatArray = lock.withLock {
        check(!textOnly) { "Loaded without the audio encoder" }
        check(!suspended) { "Embedder suspended while sessions are processed" }
        if (engine == null) loadLocked()
        val e = checkNotNull(engine) { "Embedder not loaded" }
        try {
            withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) { runAudio(e, pcm, ctx, role) }
        } finally {
            closeIfRequested()
        }
    }

    /** Embeds text as a query ("task: search query | text: ...") or as a document. */
    suspend fun embedText(text: String, query: Boolean, ctx: LogCtx): FloatArray = lock.withLock {
        check(!suspended) { "Embedder suspended while sessions are processed" }
        if (engine == null) loadLocked()
        val e = checkNotNull(engine) { "Embedder not loaded" }
        try {
            withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) { runText(e, text, query, ctx) }
        } finally {
            closeIfRequested()
        }
    }

    private fun runAudio(e: EmbeddingEngine, pcm: ShortArray, ctx: LogCtx, role: String): FloatArray {
        val audioSec = Pcm.seconds(pcm)
        val wav = AppLog.span(TAG, "audio_wav_encode", ctx, "samples" to pcm.size, "audio_sec" to "%.2f".format(audioSec)) { span ->
            Pcm.toWav(pcm).also { span.put("wav_bytes", it.size) }
        }
        return AppLog.spanRedacted(TAG, "embed", ctx, "modality" to "audio", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "audio_backend" to "CPU", "role" to role, "audio_sec" to "%.2f".format(audioSec)) { span ->
            val t0 = System.nanoTime()
            val v = e.computeEmbedding(listOf(InputData.Audio(wav)), options()).embedding
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            span.put("rtf", "%.3f".format(ms / 1000.0 / audioSec))
            finish(v, span)
        }
    }

    private fun runText(e: EmbeddingEngine, text: String, query: Boolean, ctx: LogCtx): FloatArray =
        AppLog.spanRedacted(TAG, "embed", ctx, "modality" to "text", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "role" to if (query) "query" else "document", "text_chars" to text.length) { span ->
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
            if (textOnly) return@span
            val tone = ShortArray(Pcm.SAMPLE_RATE) { (8000 * sin(2 * Math.PI * 440 * it / Pcm.SAMPLE_RATE)).toInt().toShort() }
            val a = runAudio(e, tone, LogCtx.NONE, "selftest")
            span.put("audio_dim", a.size)
            check(a.size == dim) { "unexpected audio embedding size ${a.size}" }
        }
    }

    /**
     * Unloads the engine, never underneath a running call: if one holds the lock, the engine is closed as soon as it is done. Never blocks, so
     * it is safe from the main thread and from the system's memory callbacks.
     */
    fun close() {
        if (lock.tryLock()) {
            try {
                closeLocked()
            } finally {
                lock.unlock()
            }
        } else {
            closeRequested = true
            AppLog.i(TAG, "embed_close_deferred", LogCtx.NONE, "reason" to "call_in_flight")
        }
    }

    /**
     * The system asked for memory back: unload, and for [MemoryConfig.LOW_MEMORY_CPU_COOLDOWN_MS] reload on the CPU only, so the next search
     * does not take the same GPU/NPU memory again straight away.
     */
    fun releaseForMemory() {
        if (backend != "none" && !Accelerators.isFallback(backend)) cpuOnlyUntil = SystemClock.elapsedRealtime() + MemoryConfig.LOW_MEMORY_CPU_COOLDOWN_MS
        AppLog.w(TAG, "embed_release_for_memory", LogCtx.NONE, "backend" to backend, "cpu_only_for_ms" to (cpuOnlyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        close()
    }

    private fun closeIfRequested() {
        if (closeRequested) closeLocked()
    }

    private fun closeLocked() {
        closeRequested = false
        val e = engine ?: return
        engine = null
        val t0 = System.nanoTime()
        runCatching { e.close() }.onFailure { AppLog.e(TAG, "embed_close_failed", LogCtx.NONE, it, "backend" to backend) }
        runCatching { Backends.guard(context).unloaded(guardModel, backend) }
        AppLog.i(TAG, "embed_unloaded", LogCtx.NONE, "model" to guardModel, "backend" to backend, "close_ms" to (System.nanoTime() - t0) / 1_000_000, "cpu_only_for_ms" to (cpuOnlyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        backend = "none"
    }

    /** Unloads the engine and refuses work until [resume]: the LLM that processes sessions must not share memory with it. */
    suspend fun suspend() = lock.withLock {
        suspended = true
        closeLocked()
    }

    /** Allows work again; the engine loads on its next use. */
    fun resume() {
        suspended = false
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
