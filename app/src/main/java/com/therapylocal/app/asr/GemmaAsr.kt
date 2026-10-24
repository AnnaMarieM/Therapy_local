package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.data.TranscriptClean
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.NpuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Gemma 4 E2B audio input on LiteRT-LM. Backend order is NPU, then GPU, then CPU, each validated with a warm-up reply. */
class GemmaAsr(private val context: Context) : AsrEngine {
    override val engineName = "gemma"
    override val modelName = ModelRegistry.GEMMA.id
    override var backend: String = "none"
        private set

    private var engine: Engine? = null
    private val lock = Mutex()

    override suspend fun load() = lock.withLock {
        if (engine != null) return@withLock
        withContext(Dispatchers.Default) {
            val file = ModelRegistry.GEMMA.file(context, ModelRegistry.GEMMA.files.first())
            AppLog.span(TAG, "asr_load", LogCtx.NONE, "engine" to engineName, "model" to modelName, "model_mb" to (file.length() shr 20)) { span ->
                // NPU only when the Qualcomm dispatch library is really there (see NpuRuntime); otherwise the label would be a lie.
                val attempts = ArrayList<Pair<String, () -> Backend>>()
                if (BackendPolicy.allows("NPU")) {
                    if (NpuRuntime.available(context) || BackendPolicy.override == "npu") {
                        attempts += "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) }
                    } else {
                        AppLog.w(TAG, "npu_skipped", LogCtx.NONE, "engine" to engineName, "reason" to "dispatch_library_missing", "expected" to NpuRuntime.DISPATCH_LIB)
                    }
                }
                if (BackendPolicy.allows("GPU")) attempts += "GPU" to { Backend.GPU() }
                if (BackendPolicy.allows("CPU")) attempts += "CPU" to { Backend.CPU() }
                val failures = ArrayList<String>()
                for ((name, make) in attempts) {
                    try {
                        val loaded = AppLog.span(TAG, "engine_init", LogCtx.NONE, "engine" to engineName, "backend_try" to name) {
                            val e = Engine(
                                EngineConfig(
                                    modelPath = file.path,
                                    backend = make(),
                                    audioBackend = Backend.CPU(),
                                    cacheDir = context.cacheDir.path,
                                ),
                            )
                            e.initialize()
                            // GPU/NPU can fail only at the first inference, so validate with a tiny reply.
                            AppLog.span(TAG, "engine_warmup", LogCtx.NONE, "backend_try" to name) {
                                e.createConversation().use { it.sendMessage("Reply with OK.") }
                            }
                            e
                        }
                        engine = loaded
                        backend = name
                        span.put("backend", name)
                        span.put("fallbacks_failed", failures.size)
                        AppLog.i(TAG, "backend_selected", LogCtx.NONE, "engine" to engineName, "backend" to name, "model_file" to file.name, "model_bytes" to file.length())
                        return@span
                    } catch (t: Throwable) {
                        failures += "$name: ${t.message}"
                        AppLog.w(TAG, "backend_failed", LogCtx.NONE, "engine" to engineName, "backend_try" to name, "reason" to t.message)
                    }
                }
                error("No backend could load Gemma. " + failures.joinToString("; "))
            }
        }
    }

    override suspend fun transcribe(pcm: ShortArray, ctx: LogCtx): AsrResult = lock.withLock {
        val e = checkNotNull(engine) { "Gemma not loaded" }
        withContext(Dispatchers.Default) {
            val audioSec = Pcm.seconds(pcm)
            val wav = AppLog.span(TAG, "audio_wav_encode", ctx, "samples" to pcm.size, "audio_sec" to "%.2f".format(audioSec)) { span ->
                Pcm.toWav(pcm).also { span.put("wav_bytes", it.size) }
            }
            val start = SystemClock.elapsedRealtime()
            val text = AppLog.span(TAG, "asr_transcribe", ctx, "engine" to engineName, "model" to modelName, "backend" to backend, "audio_sec" to "%.2f".format(audioSec)) { span ->
                val sb = StringBuilder()
                var msgs = 0
                e.createConversation().use { conversation ->
                    conversation.sendMessageAsync(Contents.of(Content.AudioBytes(wav), Content.Text(PROMPT)))
                        .collect {
                            msgs++
                            sb.append(it.toString())
                        }
                }
                val raw = sb.toString().trim()
                // Observed on device: when a chunk ends mid-sentence, Gemma can echo the instruction into the transcript.
                val out = TranscriptClean.stripPromptEcho(raw)
                if (out.length != raw.length) AppLog.w(TAG, "prompt_echo_removed", ctx, "chars_removed" to (raw.length - out.length))
                val wall = SystemClock.elapsedRealtime() - start
                span.put("chars", out.length)
                span.put("stream_msgs", msgs)
                span.put("rtf", "%.2f".format(wall / 1000.0 / audioSec))
                span.put("chars_per_sec", "%.1f".format(out.length * 1000.0 / wall.coerceAtLeast(1)))
                out
            }
            AppLog.text(TAG, "transcript", ctx, text)
            AsrResult(text, audioSec, SystemClock.elapsedRealtime() - start)
        }
    }

    override fun close() {
        engine?.close()
        engine = null
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        private const val PROMPT = "Transcribe the speech in this audio verbatim. Output only the transcript, nothing else."
    }
}
