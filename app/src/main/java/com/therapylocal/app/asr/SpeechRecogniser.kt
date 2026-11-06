package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineProxy
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelRuntime
import com.therapylocal.app.models.SpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * NVIDIA Canary-1B-v2 (int8): 25 European languages, Czech among them. The only speech recogniser (docs/AD/AD-02.md), chosen for its Czech:
 * 12.7% word error rate on Czech speech (see qa/asr_bench), without falling into repeating loops. The recogniser runs in an [EngineHost];
 * this class keeps the file check, the ladder with its guard and the transcript log line in main. It imports nothing native.
 *
 * [ladder] and [guard] are test seams; production uses the defaults.
 */
class SpeechRecogniser(
    private val context: Context,
    private val language: SpeechLanguage,
    host: EngineHost = Engines.batchHost(context),
    private val provider: String = "cpu",
    private val ladder: () -> BackendLadder = { Backends.ladder(context, TAG, ENGINE) },
    private val guard: () -> BackendGuard? = { Backends.guard(context) },
    private val proxy: EngineProxy = EngineProxy(context, { host }, TAG, ENGINE),
) : AsrEngine {
    private val spec = ModelRegistry.CANARY_1B_V2
    override val engineName = ENGINE
    override val modelName = spec.id
    override var backend: String = "none"
        private set

    private var handle: Int? = null
    private val lock = Mutex()

    override suspend fun load() = lock.withLock {
        if (handle != null) return@withLock
        withContext(Dispatchers.Default) {
            val bytes = runCatching { spec.files.sumOf { spec.file(context, it).length() } }.getOrDefault(0L)
            AppLog.span(TAG, "asr_load", LogCtx.NONE, "engine" to engineName, "model" to modelName, "model_mb" to (bytes shr 20), "provider" to provider, "threads" to THREADS, "language" to language.code) { span ->
                checkFiles()
                // The provider asked for (an evaluation's "nnapi") goes first, then every accelerator with an ONNX binding that serves this model.
                val asked = Accelerators.all().firstOrNull { it.onnxProvider == provider }?.id ?: provider.uppercase()
                val loaded = ladder().load(
                    model = spec.id,
                    candidates = listOf(asked) + Accelerators.order(spec.id, ModelRuntime.ONNX),
                    gate = { id -> if (Accelerators.get(id) == null) null else Accelerators.unavailable(context, id, spec.id, ModelRuntime.ONNX) },
                    open = { name -> proxy.open(EngineKind.ASR, spec.id, spec.id, name, language = language.code) },
                    close = { runCatching { proxy.closeNow(it.handle) } },
                )
                handle = loaded.engine.handle
                backend = loaded.backend
                span.put("backend", backend)
                span.put("load_id", loaded.loadId)
            }
        }
    }

    /**
     * Every file is checked to be there and complete first: sherpa-onnx can abort natively on a missing or cut-off model, which no catch
     * sees. It needs no native code, so it runs here, before the ladder.
     */
    private fun checkFiles() {
        val files = spec.files.map { f -> Triple(f.name, spec.file(context, f).length(), f.size) }
        AppLog.i(
            TAG, "asr_files", LogCtx.NONE, "engine" to engineName, "model" to spec.id,
            "files" to files.joinToString(",") { (n, have, want) -> "$n:$have/$want" }, "complete" to files.all { it.second == it.third },
        )
        val incomplete = files.filter { it.second != it.third }
        check(incomplete.isEmpty()) { "Speech model ${spec.id} incomplete on disk: " + incomplete.joinToString { "${it.first} ${it.second}/${it.third} bytes" } }
    }

    override suspend fun transcribe(pcm: ShortArray, ctx: LogCtx): AsrResult {
        // Before any IPC: a longer clip cannot be taken and must not cross the boundary.
        require(pcm.size <= LiveCueConfig.MAX_LOOKBACK_SAMPLES) { "audio too long: ${pcm.size} samples, at most ${LiveCueConfig.MAX_LOOKBACK_SAMPLES}" }
        return lock.withLock {
            val h = checkNotNull(handle) { "Canary not loaded" }
            withContext(Dispatchers.Default) {
                val audioSec = Pcm.seconds(pcm)
                val start = SystemClock.elapsedRealtime()
                val text = proxy.call<EngineReply.Text>(EngineOp.Transcribe(proxy.newTag("transcribe", null), h, pcm)).text
                AppLog.text(TAG, "transcript", ctx, text)
                AsrResult(text, audioSec, SystemClock.elapsedRealtime() - start)
            }
        }
    }

    override fun close() {
        val h = handle ?: return
        handle = null
        proxy.close(h)
        runCatching { guard()?.unloaded(spec.id, backend) }
        AppLog.i(TAG, "asr_unloaded", LogCtx.NONE, "engine" to engineName, "model" to spec.id, "backend" to backend)
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        const val ENGINE = "canary"
        private const val THREADS = 4
    }
}
