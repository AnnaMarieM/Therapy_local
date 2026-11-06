package com.therapylocal.app.embed

import android.content.Context
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.LocalEngineHost

/**
 * The process-wide [Embedder]. A recording outlives the screen that started it (see SessionController), so live cues and search share one
 * loaded model instead of each view model loading its own.
 */
object Engines {
    @Volatile
    private var embedder: Embedder? = null

    private val hosts = java.util.concurrent.ConcurrentHashMap<EngineRole, EngineHost>()

    /** The host for live cues and search (EmbeddingGemma with its audio encoder). In-process until the remote hosts arrive (S8); `EngineConfig.IN_PROCESS` forces this later too. */
    fun liveHost(context: Context): EngineHost = host(context, EngineRole.LIVE)

    /** The host for the text LLM, the text embedder and Canary. */
    fun batchHost(context: Context): EngineHost = host(context, EngineRole.BATCH)

    private fun host(context: Context, role: EngineRole): EngineHost =
        hosts.getOrPut(role) { LocalEngineHost(context.applicationContext, role) }

    /**
     * Part of `AppLog.clear()` in main: the hosts' own log files go too. Best effort and it never binds a host. A bound host is asked with
     * `EngineOp.ClearLogs` (S8 adds that for `RemoteEngineHost`); the files of roles that are not bound are deleted through [deleteFiles].
     * Until the remote hosts exist every host is in-process and no role is bound, so only the files are deleted. A running host that lost its
     * file re-opens a new one on its next line.
     */
    fun clearHostLogs(deleteFiles: (EngineRole) -> Unit) {
        for (role in listOf(EngineRole.LIVE, EngineRole.BATCH)) runCatching { deleteFiles(role) }
    }

    fun embedder(context: Context): Embedder =
        embedder ?: synchronized(this) { embedder ?: Embedder(context.applicationContext, host = liveHost(context)).also { embedder = it } }

    /** Unloads the shared embedder (if it was created) while sessions are processed. Live cues and search skip it until [resume]. */
    suspend fun suspend() {
        embedder?.suspend()
    }

    fun resume() {
        embedder?.resume()
    }

    /**
     * Frees the embedder when the app is backgrounded or memory is short, unless a session is recording (live cues need it). A call in flight
     * finishes first ([Embedder.close] never closes the engine underneath it). Only real pressure ([critical]) keeps reloads on the CPU for a
     * while: leaving the app is not a reason to give up the GPU.
     */
    fun onLowMemory(critical: Boolean) {
        embedder?.let { e ->
            if (!com.therapylocal.app.jobs.JobRunner.recordingActive) {
                if (critical) e.releaseForMemory() else e.close()
                com.therapylocal.app.log.AppLog.w("Memory", "proactive_embedder_unload", com.therapylocal.app.log.LogCtx.NONE, "critical" to critical)
            }
        }
        System.gc()
    }
}
