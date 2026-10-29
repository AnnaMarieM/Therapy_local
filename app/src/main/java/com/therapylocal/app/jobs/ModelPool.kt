package com.therapylocal.app.jobs

import android.content.Context
import com.therapylocal.app.asr.AsrEngine
import com.therapylocal.app.asr.SherpaWhisperAsr
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.memory.MemoryLlm
import kotlinx.coroutines.runBlocking

/**
 * The models the handlers use, each loaded on first use and kept across jobs until [close] (the end of a drain), so a multi-GB model is not
 * reloaded per job. There is one Gemma engine per pool: Gemma speech recognition is to share it rather than load the same file a second time.
 */
class ModelPool(private val context: Context) : AutoCloseable {
    private var llm: MemoryLlm? = null
    private var embedder: Embedder? = null
    private var asr: AsrEngine? = null

    /** The first call decides [maxTokens] and the backend order; later calls get the loaded engine. */
    fun llm(maxTokens: Int, backends: List<String>): MemoryLlm =
        llm ?: MemoryLlm(context, maxTokens).loaded({ it.load(backends) }, MemoryLlm::close).also { llm = it }

    /** On CPU: the LLM holds the GPU, and both on the GPU at once can run out of memory on phones. */
    fun embedder(): Embedder = embedder ?: Embedder(context, forcedBackend = "CPU").loaded({ runBlocking { it.load() } }, Embedder::close).also { embedder = it }

    fun whisper(): AsrEngine = asr ?: run {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        SherpaWhisperAsr(context, prefs.getString("whisper_size", "base") ?: "base", prefs.getString("language", "auto") ?: "auto")
            .loaded({ runBlocking { it.load() } }, AsrEngine::close).also { asr = it }
    }

    override fun close() {
        asr?.close()
        embedder?.close()
        llm?.close()
        asr = null
        embedder = null
        llm = null
    }

    /** A model that fails to load is closed and not kept, so the next job tries again. */
    private inline fun <T> T.loaded(load: (T) -> Unit, close: (T) -> Unit): T = try {
        load(this)
        this
    } catch (t: Throwable) {
        close(this)
        throw t
    }
}
