package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * NVIDIA Parakeet-TDT 0.6B v3 (int8) on sherpa-onnx: a transducer that recognises 25 European languages, Czech among them, and finds the
 * language by itself. Unlike Whisper's sequence decoder it cannot fall into a repeating loop or invent text over silence.
 */
class SherpaParakeetAsr(private val context: Context, private val provider: String = "cpu") : AsrEngine {
    private val spec = ModelRegistry.PARAKEET_V3
    override val engineName = "parakeet"
    override val modelName = spec.id
    override var backend: String = "none"
        private set

    private var recognizer: OfflineRecognizer? = null
    private val lock = Mutex()

    override suspend fun load() = lock.withLock {
        if (recognizer != null) return@withLock
        withContext(Dispatchers.Default) {
            val bytes = spec.files.sumOf { spec.file(context, it).length() }
            AppLog.span(TAG, "asr_load", LogCtx.NONE, "engine" to engineName, "model" to modelName, "model_mb" to (bytes shr 20), "provider" to provider, "threads" to THREADS) { span ->
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = Pcm.SAMPLE_RATE, featureDim = 128),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = spec.path(context, "encoder.int8.onnx"),
                            decoder = spec.path(context, "decoder.int8.onnx"),
                            joiner = spec.path(context, "joiner.int8.onnx"),
                        ),
                        tokens = spec.path(context, "tokens.txt"),
                        numThreads = THREADS,
                        provider = provider,
                        modelType = "nemo_transducer",
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
        val r = checkNotNull(recognizer) { "Parakeet not loaded" }
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
