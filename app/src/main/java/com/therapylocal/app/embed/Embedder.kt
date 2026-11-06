package com.therapylocal.app.embed

import android.content.Context
import android.os.SystemClock
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineProxy
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.LlmMemory
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.ModelRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * The native engine (and the model-info read, the self-test and the embed spans) lives in an [EngineHost]; this class keeps the ladder with its
 * guard, the memory gates, the lock and the lifecycle. It imports nothing native. [ladder], [guard], [gate] and [proxy] are test seams.
 */
class Embedder(
    private val context: Context,
    private val forcedBackend: String? = null,
    /** Load only the text encoder: the audio encoder is left out (the pipeline embeds text only). [embedAudio] then fails. */
    private val textOnly: Boolean = false,
    host: EngineHost = if (textOnly) Engines.batchHost(context) else Engines.liveHost(context),
    private val ladder: () -> BackendLadder = { Backends.ladder(context, TAG, "embeddinggemma") },
    private val guard: () -> BackendGuard? = { Backends.guard(context) },
    gate: ((String, Boolean) -> String?)? = null,
    private val proxy: EngineProxy = EngineProxy(context, { host }, TAG, "embeddinggemma"),
) {
    var backend: String = "none"
        private set
    val dim = EmbeddingConfig.DIM

    /** [EmbeddingConfig.MAX_TOKENS], lowered to the context the model reports. Known after [load]. */
    var maxTokens: Int = EmbeddingConfig.MAX_TOKENS
        private set

    /** Longest audio clip one call can take, from [maxTokens]. */
    val maxAudioSeconds get() = EmbeddingConfig.maxAudioSeconds(maxTokens)

    private val gateFn: (String, Boolean) -> String? = gate ?: { name, forced -> memoryGate(name, forced) }
    private var handle: Int? = null

    /** False after a reply the proxy rejected ("bad embedding"): the next use closes the handle and reloads. */
    @Volatile
    var healthy: Boolean = true
        private set
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
        if (handle != null) return
        withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) {
            val fileInfo = runCatching { ModelRegistry.EMBED.file(context, ModelRegistry.EMBED.files.first()).let { it.name to it.length() } }.getOrNull()
            AppLog.span(TAG, "embed_load", LogCtx.NONE, "model" to EmbeddingConfig.MODEL_ID, "model_mb" to ((fileInfo?.second ?: 0L) shr 20), "dim" to dim) { span ->
                val preferred = Accelerators.order(EmbeddingConfig.MODEL_ID, ModelRuntime.LITERT)
                val candidates = if (forcedBackend != null) BackendPolicy.order(listOf(Accelerators.fallback.id), forcedBackend) else BackendPolicy.order(preferred)
                val forced = forcedBackend != null || !BackendPolicy.override.equals("auto", ignoreCase = true)
                AppLog.i(
                    TAG, "embed_load_request", LogCtx.NONE, "guard_model" to guardModel, "text_only" to textOnly, "forced_backend" to forcedBackend,
                    "override" to BackendPolicy.override, "candidates" to candidates.joinToString(","), "max_tokens" to maxTokens,
                    "cpu_only_for_ms" to (cpuOnlyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                )
                val loaded = ladder().load(
                    model = guardModel,
                    candidates = candidates,
                    gate = { name -> gateFn(name, forced) },
                    open = { name -> proxy.open(EngineKind.EMBED, EmbeddingConfig.MODEL_ID, guardModel, name, maxTokens = EmbeddingConfig.MAX_TOKENS, textOnly = textOnly) },
                    close = { runCatching { proxy.closeNow(it.handle) } },
                )
                handle = loaded.engine.handle
                backend = loaded.backend
                healthy = true
                // The host lowers the input length to what the model reports.
                maxTokens = loaded.engine.maxTokens.takeIf { it > 0 } ?: maxTokens
                // Only logging from here on: nothing may throw past the point where the engine is ours.
                runCatching {
                    span.put("max_tokens", maxTokens)
                    span.put("load_id", loaded.loadId)
                    span.put("backend", backend)
                    span.put("audio_backend", loaded.engine.audioBackend ?: audioBackendName())
                    span.put("fallbacks_failed", loaded.skipped.size)
                    AppLog.i(TAG, "backend_selected", LogCtx.NONE, "engine" to "embeddinggemma", "load_id" to loaded.loadId, "backend" to backend, "audio_backend" to (loaded.engine.audioBackend ?: audioBackendName()), "model_file" to fileInfo?.first, "model_bytes" to fileInfo?.second, "skipped" to loaded.skipped.joinToString("; "))
                    AppLog.logMemory(context, TAG, "embed_ready", extra = arrayOf("backend" to backend, "host_pss_kb" to proxy.status()?.pssKb))
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
    private fun memoryGate(name: String, forced: Boolean): String? {
        Accelerators.unavailable(context, name, EmbeddingConfig.MODEL_ID, ModelRuntime.LITERT)?.let { return it }
        if (Accelerators.get(name)?.takesSystemMemory != true) return null
        if (SystemClock.elapsedRealtime() < cpuOnlyUntil) return "CPU only for a while after the system asked for memory"
        if (Accelerators.get(name)?.graphicsMemory == true && !forced && !com.therapylocal.app.memory.DeviceMemory.allowEmbedderGpu(context)) return "memory safety: Tensor/Mali GPU or not enough RAM"
        val (available, low) = LlmMemory.available(context)
        val bytes = ModelRegistry.EMBED.totalBytes
        if (!LlmMemory.gpuFits(bytes, available, low)) return "not enough free memory (${available shr 20} MB free, ${LlmMemory.gpuNeeds(bytes) shr 20} MB needed)"
        return null
    }

    private fun audioBackendName() = if (textOnly) "none" else "CPU"

    /** Embeds the AUDIO of one chunk directly. No transcript is involved. */
    suspend fun embedAudio(pcm: ShortArray, ctx: LogCtx, role: String = "document"): FloatArray {
        // Before any IPC: a longer clip cannot be embedded and must not cross the boundary.
        require(pcm.size <= LiveCueConfig.MAX_LOOKBACK_SAMPLES) { "audio too long: ${pcm.size} samples, at most ${LiveCueConfig.MAX_LOOKBACK_SAMPLES}" }
        return lock.withLock {
            check(!textOnly) { "Loaded without the audio encoder" }
            check(!suspended) { "Embedder suspended while sessions are processed" }
            prepareLocked()
            val h = checkNotNull(handle) { "Embedder not loaded" }
            try {
                withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) {
                    vectorOf { proxy.call<EngineReply.Vector>(EngineOp.EmbedAudio(proxy.newTag("embed_audio", null), h, pcm, role)) }
                }
            } finally {
                closeIfRequested()
            }
        }
    }

    /** Embeds text as a query ("task: search query | text: ...") or as a document. */
    suspend fun embedText(text: String, query: Boolean, ctx: LogCtx): FloatArray = lock.withLock {
        check(!suspended) { "Embedder suspended while sessions are processed" }
        prepareLocked()
        val h = checkNotNull(handle) { "Embedder not loaded" }
        try {
            withContext(com.therapylocal.app.jobs.ModelDispatchers.Compute) {
                vectorOf { proxy.call<EngineReply.Vector>(EngineOp.EmbedText(proxy.newTag("embed_text", null), h, text, query)) }
            }
        } finally {
            closeIfRequested()
        }
    }

    /** Under the lock: drops a handle a bad reply made untrustworthy, then loads if there is none. */
    private suspend fun prepareLocked() {
        if (!healthy) closeLocked()
        if (handle == null) loadLocked()
    }

    /** A bad vector never leaves: the embedder turns unhealthy (reloads on next use) and the error is thrown. */
    private inline fun vectorOf(call: () -> EngineReply.Vector): FloatArray {
        val reply = call()
        try {
            return proxy.vector(reply, dim)
        } catch (t: IllegalStateException) {
            healthy = false
            AppLog.e(TAG, "embed_bad_vector", LogCtx.NONE, t, "backend" to backend, "got_dim" to reply.v.size, "expected_dim" to dim)
            throw t
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
        val h = handle ?: return
        handle = null
        val t0 = System.nanoTime()
        // Never blocks, so this stays safe from the main thread (App.onTrimMemory).
        proxy.close(h)
        runCatching { guard()?.unloaded(guardModel, backend) }
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
