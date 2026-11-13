package com.therapylocal.app.engine.runner

import android.content.Context
import android.os.SystemClock
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelRegistry

/** The JNI side of whisper.cpp (`tools/whisper-android`, `libwhisper_jni.so`, arm64 only). Loaded in the `:batch` host, never in the main process. */
internal object WhisperLib {
    val available: Boolean = try {
        System.loadLibrary("whisper_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeRun(handle: Long, samples: FloatArray, language: String, translate: Boolean, beam: Int, threads: Int, prompt: String?): String?
    @JvmStatic external fun nativeTimings(handle: Long): FloatArray
    @JvmStatic external fun nativeSystemInfo(): String
}

/**
 * Whisper large-v3 (ggml q8_0) through whisper.cpp on the CPU, for the Quality workflow (docs/AD/AD-22.md): one context on one backend, one call per
 * turn. With [EngineOp.Transcribe.translate] the model writes English whatever language is spoken (the Whisper translate task); there is no text in
 * the language spoken then. The weights are the same on every device and the backend is the CPU only (AD-21: the same model on every backend).
 */
internal class WhisperRunner(private val context: Context) : AsrEngine {
    override val kind = EngineKind.ASR
    override var model: String = ""
        private set
    override var backend: String = "none"
        private set
    private var handle = 0L

    /** One ladder attempt on the CPU: load the weights and decode one second of silence. One that fails is freed here. */
    fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply.Opened {
        val spec = ModelRegistry.byId(op.model)
        val f = callFields(op.tag)
        check(WhisperLib.available) { "libwhisper_jni.so is not in this build (arm64 only)" }
        notePhase(phases, op.tag, TAG, op.guardModel, op.backend, "create")
        val path = spec.path(context, spec.files.first().name)
        val h = AppLog.span(TAG, "asr_recognizer_create", LogCtx.NONE, "engine" to ENGINE, "backend_try" to op.backend, "threads" to THREADS, *f) { WhisperLib.nativeInit(path) }
        check(h != 0L) { "whisper model did not load" }
        try {
            notePhase(phases, op.tag, TAG, op.guardModel, op.backend, "test")
            AppLog.span(TAG, "asr_selftest", LogCtx.NONE, "engine" to ENGINE, "backend_try" to op.backend, *f) { span ->
                val out = WhisperLib.nativeRun(h, FloatArray(Pcm.SAMPLE_RATE), op.language ?: "en", false, 1, THREADS, null)
                checkNotNull(out) { "whisper self-test failed" }
                span.put("chars", out.length)
                span.put("system_info", WhisperLib.nativeSystemInfo().trim())
            }
        } catch (t: Throwable) {
            runCatching { WhisperLib.nativeFree(h) }
            throw t
        }
        handle = h
        model = spec.id
        backend = op.backend
        return EngineReply.Opened(0, op.backend, 0, null)
    }

    override fun transcribe(op: EngineOp.Transcribe): EngineReply.Text {
        check(handle != 0L) { "Whisper not loaded" }
        val audioSec = Pcm.seconds(op.pcm)
        val samples = Pcm.toFloats(op.pcm)
        val start = SystemClock.elapsedRealtime()
        val text = AppLog.spanRedacted(
            TAG, "asr_transcribe", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, "audio_sec" to "%.2f".format(audioSec),
            "task" to if (op.translate) "translate" else "transcribe", "beam" to op.beam, *callFields(op.tag),
        ) { span ->
            val out = checkNotNull(WhisperLib.nativeRun(handle, samples, op.language ?: "", op.translate, op.beam.coerceAtLeast(1), THREADS, null)) { "whisper run failed" }.trim()
            val wall = SystemClock.elapsedRealtime() - start
            val t = WhisperLib.nativeTimings(handle)
            span.put("chars", out.length)
            span.put("rtf", "%.2f".format(wall / 1000.0 / audioSec.coerceAtLeast(0.01)))
            span.put("encode_ms", "%.0f".format(t[0]))
            span.put("decode_ms", "%.0f".format(t[1]))
            out
        }
        AppLog.text(TAG, "transcript", LogCtx.NONE, text)
        return EngineReply.Text(text, null)
    }

    override fun close(tag: CallTag) {
        val h = handle
        if (h == 0L) return
        handle = 0L
        val f = callFields(tag)
        runCatching { WhisperLib.nativeFree(h) }.onFailure { AppLog.e(TAG, "asr_release_failed", LogCtx.NONE, it, "engine" to ENGINE, *f) }
        AppLog.i(TAG, "asr_unloaded", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, *f)
        backend = "none"
    }

    companion object {
        const val TAG = "ASR"
        private const val ENGINE = "whisper"

        /** Threads for the decoder and encoder: the research benchmark used 4 on the tablet and the Pixel and 6 on the Poco; 4 everywhere keeps one rule and leaves cores for the system. */
        private const val THREADS = 4
    }
}
