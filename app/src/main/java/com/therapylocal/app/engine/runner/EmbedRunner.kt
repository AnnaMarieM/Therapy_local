package com.therapylocal.app.engine.runner

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.log.Span
import com.therapylocal.app.models.LiteRtBindings
import com.therapylocal.app.models.ModelRegistry
import java.io.File
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The native half of the embedder (EmbeddingGemma 2 on LiteRT-LM): one engine on one backend, text and (unless [textOnly]) audio into one
 * 768-d space. The ladder, memory gates, suspend/resume and the cooldown stay in the main process (`Embedder`). The `embed` span with
 * norm and rtf is DEBUG in `:live` (a tick every 2 s) and INFO elsewhere.
 */
internal class EmbedRunner(private val context: Context, private val role: EngineRole, private val textOnly: Boolean) : EmbedEngine {
    override val kind = EngineKind.EMBED
    override var model: String = ""
        private set
    override var backend: String = "none"
        private set
    private var engine: EmbeddingEngine? = null
    private var maxTokens = EmbeddingConfig.MAX_TOKENS
    private val debugSpans get() = role == EngineRole.LIVE

    /** One ladder attempt: create, initialise and self-test an engine on [op].backend. One that fails on the way is closed here. */
    fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply.Opened {
        val spec = ModelRegistry.byId(op.model)
        val file = spec.file(context, spec.files.first())
        val name = op.backend
        val f = callFields(op.tag)
        maxTokens = logModelInfo(file, f)
        val audioName = if (textOnly) "none" else "CPU"
        notePhase(phases, op.tag, TAG, op.guardModel, name, "create")
        val created = AppLog.span(TAG, "embed_engine_init", LogCtx.NONE, "backend_try" to name, "audio_backend" to audioName, *f) {
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
            notePhase(phases, op.tag, TAG, op.guardModel, name, "initialize")
            AppLog.span(TAG, "embed_engine_initialize", LogCtx.NONE, "backend_try" to name, *f) { created.initialize() }
            notePhase(phases, op.tag, TAG, op.guardModel, name, "test")
            backend = name
            selfTest(created, name, f)
        } catch (t: Throwable) {
            backend = "none"
            runCatching { created.close() }.onFailure { AppLog.w(TAG, "embed_engine_close_after_failure_failed", LogCtx.NONE, "backend_try" to name, "reason" to it.message, *f) }
            throw t
        }
        engine = created
        model = spec.id
        return EngineReply.Opened(0, name, maxTokens, audioName.takeIf { !textOnly })
    }

    /** What the model bundle itself declares: modalities, backends, NPU brand. Audio support is required unless text only. Returns the input length to use. */
    private fun logModelInfo(file: File, f: Fields): Int =
        ModelInfo.from(file.path).use { info ->
            val m = info.inputModalities()
            AppLog.i(
                TAG, "model_info", LogCtx.NONE,
                "text" to m.text, "audio" to m.audio, "vision" to m.vision, "max_context_tokens" to info.maxContextTokens(),
                "backends_text" to info.supportedBackends(Modality.TEXT).joinToString("/") { it.name },
                "backends_audio" to info.supportedBackends(Modality.AUDIO).joinToString("/") { it.name },
                "npu_brand" to info.npuBrand(Modality.TEXT).name, "bundle_soc" to info.socName(Modality.TEXT), *f,
            )
            check(textOnly || m.audio) { "This EmbeddingGemma build has no audio encoder" }
            val reported = info.maxContextTokens()
            if (reported in 1 until EmbeddingConfig.MAX_TOKENS) reported else EmbeddingConfig.MAX_TOKENS
        }

    private fun isolatedCacheDir(): File = File(context.cacheDir, "emb_t$maxTokens").apply { mkdirs() }

    override fun embedText(op: EngineOp.EmbedText): EngineReply.Vector =
        EngineReply.Vector(runText(checkNotNull(engine) { "Embedder not loaded" }, op.text, op.query, callFields(op.tag, op.handle)))

    override fun embedAudio(op: EngineOp.EmbedAudio): EngineReply.Vector {
        check(!textOnly) { "Loaded without the audio encoder" }
        return EngineReply.Vector(runAudio(checkNotNull(engine) { "Embedder not loaded" }, op.pcm, op.role, callFields(op.tag, op.handle)))
    }

    private fun runAudio(e: EmbeddingEngine, pcm: ShortArray, role: String, f: Fields): FloatArray {
        val audioSec = Pcm.seconds(pcm)
        val wav = AppLog.span(TAG, "audio_wav_encode", LogCtx.NONE, "samples" to pcm.size, "audio_sec" to "%.2f".format(audioSec), *f) { span ->
            Pcm.toWav(pcm).also { span.put("wav_bytes", it.size) }
        }
        return redactedSpan(debugSpans, TAG, "embed", "modality" to "audio", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "audio_backend" to "CPU", "role" to role, "audio_sec" to "%.2f".format(audioSec), *f) { span ->
            val t0 = System.nanoTime()
            val v = e.computeEmbedding(listOf(InputData.Audio(wav)), options()).embedding
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            span.put("rtf", "%.3f".format(ms / 1000.0 / audioSec))
            finish(v, span)
        }
    }

    private fun runText(e: EmbeddingEngine, text: String, query: Boolean, f: Fields): FloatArray =
        redactedSpan(debugSpans, TAG, "embed", "modality" to "text", "model" to EmbeddingConfig.MODEL_ID, "backend" to backend, "role" to if (query) "query" else "document", "text_chars" to text.length, *f) { span ->
            val prefixed = (if (query) EmbeddingConfig.QUERY_PREFIX else EmbeddingConfig.DOC_PREFIX) + text.trim()
            finish(e.computeEmbedding(listOf(InputData.Text(prefixed)), options()).embedding, span)
        }

    private fun options() = EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = EmbeddingConfig.DIM)

    private fun finish(v: FloatArray, span: Span): FloatArray {
        val norm = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()
        span.put("dim", v.size)
        span.put("norm", norm)
        // Defensive re-normalise so cosine == dot product whatever the runtime did.
        return if (norm > 0f && kotlin.math.abs(norm - 1f) > 1e-3f) FloatArray(v.size) { v[it] / norm } else v
    }

    /** Text pair similarity plus an audio smoke test (a 1 s tone). Proves both encoders run and the text space is semantic. */
    private fun selfTest(e: EmbeddingEngine, backendName: String, f: Fields) {
        val dim = EmbeddingConfig.DIM
        AppLog.span(TAG, "embed_selftest", LogCtx.NONE, "backend_try" to backendName, *f) { span ->
            val q = runText(e, "my boss keeps criticising my work", true, f)
            val related = runText(e, "my manager is always unhappy with my reports", false, f)
            val unrelated = runText(e, "we went hiking in the mountains last weekend", false, f)
            span.put("sim_related", dot(q, related))
            span.put("sim_unrelated", dot(q, unrelated))
            check(q.size == dim) { "unexpected embedding size ${q.size}" }
            check(dot(q, related) > dot(q, unrelated)) { "text self-test failed" }
            if (textOnly) return@span
            val tone = ShortArray(Pcm.SAMPLE_RATE) { (8000 * sin(2 * Math.PI * 440 * it / Pcm.SAMPLE_RATE)).toInt().toShort() }
            val a = runAudio(e, tone, "selftest", f)
            span.put("audio_dim", a.size)
            check(a.size == dim) { "unexpected audio embedding size ${a.size}" }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    override fun close(tag: CallTag) {
        val e = engine ?: return
        engine = null
        val f = callFields(tag)
        val t0 = System.nanoTime()
        runCatching { e.close() }.onFailure { AppLog.e(TAG, "embed_close_failed", LogCtx.NONE, it, "backend" to backend, *f) }
        AppLog.i(TAG, "embed_unloaded", LogCtx.NONE, "model" to model, "backend" to backend, "close_ms" to (System.nanoTime() - t0) / 1_000_000, *f)
        backend = "none"
    }

    companion object {
        const val TAG = "Embed"
    }
}
