package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whisper (tiny, base or small, int8) for one language on sherpa-onnx. [provider] is the ONNX Runtime execution provider: "cpu" or "nnapi". */
class SherpaWhisperAsr(private val context: Context, size: String, private val language: SpeechLanguage, private val provider: String = "cpu") : AsrEngine {
    private val spec = ModelRegistry.whisperFor(size, language)
    private val prefix = spec.id.removePrefix("whisper-")
    override val engineName = "whisper"
    override val modelName = spec.id
    override var backend: String = "none"
        private set

    private var recognizer: OfflineRecognizer? = null
    private val lock = Mutex()

    override suspend fun load() = lock.withLock {
        if (recognizer != null) return@withLock
        withContext(Dispatchers.Default) {
            val bytes = spec.files.sumOf { spec.file(context, it).length() }
            AppLog.span(TAG, "asr_load", LogCtx.NONE, "engine" to engineName, "model" to modelName, "model_mb" to (bytes shr 20), "provider" to provider, "threads" to THREADS, "language" to language.code) { span ->
                val whisper = OfflineWhisperModelConfig(
                    encoder = spec.path(context, "$prefix-encoder.int8.onnx"),
                    decoder = spec.path(context, "$prefix-decoder.int8.onnx"),
                    language = if (spec.id.endsWith(".en")) "" else language.code, // an English-only build has no language to set; otherwise a fixed language avoids auto-detect misfiring on short chunks
                    task = "transcribe",
                )
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = Pcm.SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = whisper,
                        tokens = spec.path(context, "$prefix-tokens.txt"),
                        numThreads = THREADS,
                        debug = false,
                        provider = provider,
                        modelType = "whisper",
                    ),
                    decodingMethod = "greedy_search",
                )
                val opened = SherpaLoad.open(context, TAG, engineName, spec, provider, config)
                recognizer = opened.recognizer
                backend = opened.backend
                span.put("backend", backend)
                span.put("load_id", opened.loadId)
            }
        }
    }

    override suspend fun transcribe(pcm: ShortArray, ctx: LogCtx): AsrResult = lock.withLock {
        val r = checkNotNull(recognizer) { "Whisper not loaded" }
        withContext(Dispatchers.Default) {
            val audioSec = Pcm.seconds(pcm)
            val samples = AppLog.span(TAG, "audio_to_float", ctx, "samples" to pcm.size, "audio_sec" to "%.2f".format(audioSec)) { Pcm.toFloats(pcm) }
            val start = SystemClock.elapsedRealtime()
            val text = AppLog.span(TAG, "asr_transcribe", ctx, "engine" to engineName, "model" to modelName, "backend" to backend, "audio_sec" to "%.2f".format(audioSec)) { span ->
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(samples, Pcm.SAMPLE_RATE)
                    AppLog.span(TAG, "whisper_decode", ctx) { r.decode(stream) }
                    val result = r.getResult(stream)
                    val out = result.text.trim()
                    val wall = SystemClock.elapsedRealtime() - start
                    span.put("chars", out.length)
                    span.put("tokens", result.tokens.size)
                    span.put("lang", result.lang)
                    span.put("rtf", "%.2f".format(wall / 1000.0 / audioSec))
                    span.put("chars_per_sec", "%.1f".format(out.length * 1000.0 / wall.coerceAtLeast(1)))
                    out
                } finally {
                    stream.release()
                }
            }
            AppLog.text(TAG, "transcript", ctx, text)
            AsrResult(text, audioSec, SystemClock.elapsedRealtime() - start)
        }
    }

    override fun close() {
        val r = recognizer ?: return
        recognizer = null
        SherpaLoad.release(context, TAG, engineName, spec, backend, r)
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        private const val THREADS = 4
    }
}
