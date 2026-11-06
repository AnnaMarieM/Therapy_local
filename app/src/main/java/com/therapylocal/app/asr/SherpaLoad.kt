package com.therapylocal.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.Backends
import com.therapylocal.app.models.ModelSpec
import com.therapylocal.app.models.ModelRuntime

/** Opening a sherpa-onnx recogniser the same way for every engine: checked files, the provider asked for with the CPU behind it, every step logged. */
internal object SherpaLoad {
    class Opened(val recognizer: OfflineRecognizer, val backend: String, val loadId: String)

    /**
     * Every file is checked to be there and complete first: sherpa-onnx can abort natively on a missing or cut-off model, which no catch
     * sees. Then [provider] is tried and, if it is not the CPU (NNAPI in the evaluations), the CPU after it. The outcome goes to [Backends.guard]
     * under the model id, so the settings screen and the logs show what this phone ran speech recognition on.
     */
    fun open(context: Context, tag: String, engine: String, spec: ModelSpec, provider: String, config: OfflineRecognizerConfig): Opened {
        val files = spec.files.map { f -> Triple(f.name, spec.file(context, f).length(), f.size) }
        AppLog.i(
            tag, "asr_files", LogCtx.NONE, "engine" to engine, "model" to spec.id,
            "files" to files.joinToString(",") { (n, have, want) -> "$n:$have/$want" }, "complete" to files.all { it.second == it.third },
        )
        val incomplete = files.filter { it.second != it.third }
        check(incomplete.isEmpty()) { "Speech model ${spec.id} incomplete on disk: " + incomplete.joinToString { "${it.first} ${it.second}/${it.third} bytes" } }
        // The provider asked for (an evaluation's "nnapi") goes first, then every accelerator with an ONNX binding that serves this model.
        val asked = Accelerators.all().firstOrNull { it.onnxProvider == provider }?.id ?: provider.uppercase()
        val loaded = Backends.ladder(context, tag, engine).load(
            model = spec.id,
            candidates = listOf(asked) + Accelerators.order(spec.id, ModelRuntime.ONNX),
            gate = { id -> if (Accelerators.get(id) == null) null else Accelerators.unavailable(context, id, spec.id, ModelRuntime.ONNX) },
            open = { backend ->
                val onnx = Accelerators.get(backend)?.onnxProvider ?: backend.lowercase()
                AppLog.span(tag, "asr_recognizer_create", LogCtx.NONE, "engine" to engine, "backend_try" to backend, "onnx_provider" to onnx, "threads" to config.modelConfig.numThreads) {
                    OfflineRecognizer(config = config.copy(modelConfig = config.modelConfig.copy(provider = onnx)))
                }
            },
            close = { runCatching { it.release() } },
        )
        return Opened(loaded.engine, loaded.backend, loaded.loadId)
    }

    /** Releases and notes that it was a clean unload. */
    fun release(context: Context, tag: String, engine: String, spec: ModelSpec, backend: String, recognizer: OfflineRecognizer) {
        runCatching { recognizer.release() }.onFailure { AppLog.e(tag, "asr_release_failed", LogCtx.NONE, it, "engine" to engine) }
        runCatching { Backends.guard(context).unloaded(spec.id, backend) }
        AppLog.i(tag, "asr_unloaded", LogCtx.NONE, "engine" to engine, "model" to spec.id, "backend" to backend)
    }
}
