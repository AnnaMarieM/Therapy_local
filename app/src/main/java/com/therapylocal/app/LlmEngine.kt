package com.therapylocal.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Gemma on LiteRT-LM. Backend order is NPU, then GPU, then CPU; each is validated with a warm-up reply. */
class LlmEngine(private val context: Context) {
    private var engine: Engine? = null
    var backendName: String = "none"
        private set

    suspend fun load(modelPath: String): String = withContext(Dispatchers.Default) {
        close()
        val attempts = listOf<Pair<String, () -> Backend>>(
            "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) },
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )
        val failures = mutableListOf<String>()
        for ((name, backend) in attempts) {
            try {
                val candidate = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend(),
                        audioBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.path,
                    )
                )
                candidate.initialize()
                candidate.createConversation().use { it.sendMessage("Reply with OK.") } // GPU/NPU can fail only at first inference
                engine = candidate
                backendName = name
                Log.i(TAG, "Engine ready on $name")
                return@withContext name
            } catch (t: Throwable) {
                Log.w(TAG, "Backend $name failed", t)
                failures += "$name: ${t.message}"
            }
        }
        error("No backend could load the model. " + failures.joinToString("; "))
    }

    /** Streams the transcript text as Gemma produces it. */
    fun transcribe(wav: ByteArray): Flow<String> = flow {
        val e = checkNotNull(engine) { "Engine not loaded" }
        e.createConversation().use { conversation ->
            conversation.sendMessageAsync(
                Contents.of(Content.AudioBytes(wav), Content.Text(PROMPT))
            ).collect { emit(it.toString()) }
        }
    }.flowOn(Dispatchers.Default)

    fun close() {
        engine?.close()
        engine = null
        backendName = "none"
    }

    companion object {
        const val TAG = "LlmEngine"
        private const val PROMPT =
            "Transcribe the speech in this audio verbatim. Output only the transcript, nothing else."
    }
}
