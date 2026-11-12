package com.therapylocal.app.asr

import android.content.Context
import android.os.SystemClock
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineProxy
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.guardRole
import com.therapylocal.app.jobs.JobFailure
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Speaker diarization in an [EngineHost] (docs/AD/AD-06.md): pyannote segmentation-3.0 and a WeSpeaker embedding on sherpa-onnx, run in `:batch`.
 * Like [SpeechRecogniser], this class keeps the file check, the ladder with its guard and the log lines in main, and imports nothing native.
 * The audio crosses to the host through shared memory in one call; only turns come back.
 *
 * [ladder] and [guard] are test seams; production uses the defaults.
 */
class SpeakerDiarizer(
    private val context: Context,
    host: EngineHost = Engines.batchHost(context),
    private val ladder: () -> BackendLadder = { Backends.ladder(context, TAG, ENGINE, host.guardRole) },
    private val guard: () -> BackendGuard? = { Backends.guard(context) },
    private val proxy: EngineProxy = EngineProxy(context, { host }, TAG, ENGINE),
) : Diarizer {
    private val spec = ModelRegistry.DIARIZATION
    private var handle: Int? = null
    var backend: String = "none"
        private set
    private val lock = Mutex()

    /** False once the host ended under the handle: [com.therapylocal.app.jobs.ModelPool] then closes this and loads another. */
    @Volatile
    override var healthy: Boolean = true
        private set

    override suspend fun load() = lock.withLock {
        if (handle != null) return@withLock
        withContext(Dispatchers.Default) {
            val bytes = runCatching { spec.files.sumOf { spec.file(context, it).length() } }.getOrDefault(0L)
            AppLog.span(TAG, "diarize_load", LogCtx.NONE, "engine" to ENGINE, "model" to spec.id, "model_mb" to (bytes shr 20)) { span ->
                checkFiles()
                val loadId = BackendLadder.newLoadId()
                // Killed for memory again and again even on the CPU: it cannot run here (unless a person forces the load).
                val judged = !BackendPolicy.forcing()
                fun tooLarge() = judged && runCatching { guard()?.tooLarge(spec.id) }.getOrNull() == true
                if (tooLarge()) {
                    AppLog.w(TAG, "model_too_large_for_device", LogCtx.NONE, "model" to spec.id, "load_id" to loadId, "when" to "before_load")
                    throw JobFailure.modelTooLarge()
                }
                // The LLM or Canary may still be closing: wait for it before the open.
                proxy.awaitPendingCloses()
                val loaded = try {
                    ladder().load(
                        loadId = loadId,
                        model = spec.id,
                        candidates = Accelerators.order(spec.id, ModelRuntime.ONNX),
                        respectBlocks = judged,
                        gate = { id -> if (Accelerators.get(id) == null) null else Accelerators.unavailable(context, id, spec.id, ModelRuntime.ONNX) },
                        open = { name -> proxy.open(EngineKind.DIARIZE, spec.id, spec.id, name, loadId = loadId) },
                        close = { runCatching { proxy.closeNow(it.handle) } },
                    )
                } catch (e: BackendLadder.NoBackend) {
                    if (tooLarge()) {
                        AppLog.w(TAG, "model_too_large_for_device", LogCtx.NONE, "model" to spec.id, "load_id" to loadId, "when" to "after_ladder")
                        throw JobFailure.modelTooLarge()
                    }
                    throw e
                }
                handle = loaded.engine.handle
                backend = loaded.backend
                healthy = true
                span.put("backend", backend)
                span.put("load_id", loaded.loadId)
            }
        }
    }

    /** Both files are checked to be there and complete first: sherpa-onnx can abort natively on a missing or cut-off model, which no catch sees. */
    private fun checkFiles() {
        val files = spec.files.map { f -> Triple(f.name, spec.file(context, f).length(), f.size) }
        AppLog.i(
            TAG, "diarize_files", LogCtx.NONE, "engine" to ENGINE, "model" to spec.id,
            "files" to files.joinToString(",") { (n, have, want) -> "$n:$have/$want" }, "complete" to files.all { it.second == it.third },
        )
        val incomplete = files.filter { it.second != it.third }
        check(incomplete.isEmpty()) { "Diarization model ${spec.id} incomplete on disk: " + incomplete.joinToString { "${it.first} ${it.second}/${it.third} bytes" } }
    }

    override suspend fun diarize(pcm: ShortArray, numSpeakersHint: Int, ctx: LogCtx): List<DiarTurn> {
        // Before any IPC: a longer recording cannot be taken and must not cross the boundary.
        require(pcm.size <= MAX_SAMPLES) { "audio too long: ${pcm.size} samples, at most $MAX_SAMPLES" }
        return lock.withLock {
            val h = checkNotNull(handle) { "diarizer not loaded" }
            withContext(Dispatchers.Default) {
                val audioSec = Pcm.seconds(pcm)
                val start = SystemClock.elapsedRealtime()
                val turns = try {
                    proxy.call<EngineReply.Turns>(EngineOp.Diarize(proxy.newTag("diarize", null), h, pcm, numSpeakersHint.coerceAtLeast(0))).turns
                } catch (d: EngineDied) {
                    // The host ended under the handle. The connection's registry already settled it in the guard, so this neither closes
                    // it nor settles it again; the pool loads another.
                    healthy = false
                    handle = null
                    backend = "none"
                    throw d
                } catch (t: Throwable) {
                    AppLog.e(TAG, "diarize_failed", ctx, t, "engine" to ENGINE, "audio_sec" to "%.2f".format(audioSec), "wall_ms" to (SystemClock.elapsedRealtime() - start))
                    throw t
                }
                AppLog.i(
                    TAG, "diarize_done", ctx, "turns" to turns.size, "speakers" to turns.map { it.speaker }.distinct().size,
                    "audio_sec" to "%.2f".format(audioSec), "wall_ms" to (SystemClock.elapsedRealtime() - start), "backend" to backend,
                )
                turns
            }
        }
    }

    override fun close() {
        val h = handle ?: return
        handle = null
        val b = backend
        proxy.close(h) { acked -> if (acked) runCatching { guard()?.unloaded(spec.id, b) } }
        AppLog.i(TAG, "diarize_unloaded", LogCtx.NONE, "engine" to ENGINE, "model" to spec.id, "backend" to backend)
        backend = "none"
    }

    companion object {
        const val TAG = "Diarize"
        const val ENGINE = "pyannote"

        /** Two hours of 16 kHz audio: 230 MB as PCM16 and twice that as float in the host. Longer takes the chunk path. */
        const val MAX_SAMPLES = 2 * 3600 * Pcm.SAMPLE_RATE
    }
}
