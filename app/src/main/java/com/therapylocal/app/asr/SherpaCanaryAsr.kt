package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * NVIDIA Canary-1B-v2 (int8) on sherpa-onnx: 25 European languages, Czech among them. The client's language is given as both source and
 * target, which makes it plain transcription. A sequence-to-sequence model like Whisper, but on Czech speech it scored 12.7% word error
 * rate against Whisper small's 49.8% (see qa/asr_bench), and it did not fall into repeating loops.
 */
class SherpaCanaryAsr(private val context: Context, private val language: SpeechLanguage, private val provider: String = "cpu") : AsrEngine {
    private val spec = ModelRegistry.CANARY_1B_V2
    override val engineName = "canary"
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
                        provider = provider,
                    ),
                    decodingMethod = "greedy_search",
                )
                recognizer = OfflineRecognizer(config = config)
                backend = provider.uppercase()
                span.put("backend", backend)
            }
        }
    }

    override suspend fun transcribe(pcm: ShortArray, ctx: LogCtx): AsrResult = lock.withLock {
        val r = checkNotNull(recognizer) { "Canary not loaded" }
        withContext(Dispatchers.Default) {
            val audioSec = Pcm.seconds(pcm)
            val samples = Pcm.toFloats(pcm)
            val start = SystemClock.elapsedRealtime()
            val text = AppLog.span(TAG, "asr_transcribe", ctx, "engine" to engineName, "model" to modelName, "backend" to backend, "audio_sec" to "%.2f".format(audioSec)) { span ->
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
            AppLog.text(TAG, "transcript", ctx, text)
            AsrResult(text, audioSec, SystemClock.elapsedRealtime() - start)
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        private const val THREADS = 4
    }
}
