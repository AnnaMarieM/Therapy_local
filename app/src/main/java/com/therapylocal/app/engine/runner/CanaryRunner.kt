package com.therapylocal.app.engine.runner

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SpeechLanguage

/**
 * The native half of NVIDIA Canary-1B-v2 (int8) on sherpa-onnx, the only speech recogniser (docs/AD/AD-02.md): one recogniser on one
 * provider. The check that every model file is complete needs no native code and runs in the main process before the ladder.
 */
internal class CanaryRunner(private val context: Context) : Runner {
    override val kind = EngineKind.ASR
    override var model: String = ""
        private set
    override var backend: String = "none"
        private set
    private var recognizer: OfflineRecognizer? = null

    /** One ladder attempt: create the recogniser on [op].backend and decode a second of silence. One that fails is released here. */
    fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply.Opened {
        val spec = ModelRegistry.byId(op.model)
        val language = SpeechLanguage.of(op.language)
        val name = op.backend
        val f = callFields(op.tag)
        val onnx = Accelerators.get(name)?.onnxProvider ?: name.lowercase()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Pcm.SAMPLE_RATE, featureDim = 128),
            modelConfig = OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = spec.path(context, "encoder.int8.onnx"),
                    decoder = spec.path(context, "decoder.int8.onnx"),
                    srcLang = language.code,
                    tgtLang = language.code,
                    usePnc = true,
                ),
                tokens = spec.path(context, "tokens.txt"),
                numThreads = THREADS,
                provider = onnx,
            ),
            decodingMethod = "greedy_search",
        )
        notePhase(phases, op.tag, TAG, op.guardModel, name, "create")
        val r = AppLog.span(TAG, "asr_recognizer_create", LogCtx.NONE, "engine" to ENGINE, "backend_try" to name, "onnx_provider" to onnx, "threads" to THREADS, "language" to language.code, *f) {
            OfflineRecognizer(config = config)
        }
        try {
            notePhase(phases, op.tag, TAG, op.guardModel, name, "test")
            selfTest(name, r, f)
        } catch (t: Throwable) {
            runCatching { r.release() }
            throw t
        }
        recognizer = r
        model = spec.id
        backend = name
        return EngineReply.Opened(0, name, 0, null)
    }

    /** Decodes one second of silence: a provider can construct fine and fail at the first decode, and "proven on this device" must mean it decoded. */
    private fun selfTest(name: String, r: OfflineRecognizer, f: Fields) {
        AppLog.span(TAG, "asr_selftest", LogCtx.NONE, "engine" to ENGINE, "backend_try" to name, *f) { span ->
            val stream = r.createStream()
            try {
                stream.acceptWaveform(FloatArray(Pcm.SAMPLE_RATE), Pcm.SAMPLE_RATE)
                r.decode(stream)
                span.put("chars", r.getResult(stream).text.length)
            } finally {
                stream.release()
            }
        }
    }

    fun transcribe(op: EngineOp.Transcribe): EngineReply.Text {
        val r = checkNotNull(recognizer) { "Canary not loaded" }
        val audioSec = Pcm.seconds(op.pcm)
        val samples = Pcm.toFloats(op.pcm)
        val start = SystemClock.elapsedRealtime()
        val text = AppLog.spanRedacted(TAG, "asr_transcribe", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, "audio_sec" to "%.2f".format(audioSec), *callFields(op.tag, op.handle)) { span ->
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, Pcm.SAMPLE_RATE)
                r.decode(stream)
                val out = r.getResult(stream).text.trim()
                val wall = SystemClock.elapsedRealtime() - start
                span.put("chars", out.length)
                span.put("rtf", "%.2f".format(wall / 1000.0 / audioSec))
                out
            } finally {
                stream.release()
            }
        }
        AppLog.text(TAG, "transcript", LogCtx.NONE, text)
        return EngineReply.Text(text, null)
    }

    override fun close(tag: CallTag) {
        val r = recognizer ?: return
        recognizer = null
        val f = callFields(tag)
        runCatching { r.release() }.onFailure { AppLog.e(TAG, "asr_release_failed", LogCtx.NONE, it, "engine" to ENGINE, *f) }
        AppLog.i(TAG, "asr_unloaded", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, *f)
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        private const val ENGINE = "canary"
        private const val THREADS = 4
    }
}
