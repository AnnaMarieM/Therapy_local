package com.therapylocal.app.embed

import android.content.Context
import com.therapylocal.app.engine.EngineConnection
import com.therapylocal.app.engine.EngineHost
import com.therapylocal.app.engine.HandleSettler
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.Backends
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.engine.LocalEngineHost
import com.therapylocal.app.engine.RemoteEngineHost

/**
 * The process-wide [Embedder]. A recording outlives the screen that started it (see SessionController), so live cues and search share one
 * loaded model instead of each view model loading its own.
 */
object Engines {
    @Volatile
    private var embedder: Embedder? = null

    private val hosts = java.util.concurrent.ConcurrentHashMap<EngineRole, EngineHost>()

    /** The host for live cues and search (EmbeddingGemma with its audio encoder): the `:live` process, or in-process under `EngineConfig.IN_PROCESS`. */
    fun liveHost(context: Context): EngineHost = host(context, EngineRole.LIVE)

    /** The host for the text LLM, the text embedder and Canary. */
    fun batchHost(context: Context): EngineHost = host(context, EngineRole.BATCH)

    /** What the settings screen shows of the hosts, published by their connections. Reading it never binds a host. */
    val state get() = com.therapylocal.app.engine.EngineState.views

    /**
     * Refreshes the PSS of a host that already exists and is bound, on IO with the `statusTimeoutMs` bound. Never creates or binds a host
     * and never runs on the main thread (the caller is a coroutine; this switches to IO itself).
     */
    suspend fun refreshPss(role: EngineRole) {
        val host = hosts[role] as? RemoteEngineHost ?: return
        com.therapylocal.app.engine.EngineState.refreshPss(host, tuning.statusTimeoutMs)
    }

    /** Every timing the remote hosts use. */
    val tuning: EngineTuning get() = com.therapylocal.app.engine.LabWiring.tuning(EngineConfig.DEFAULT)

    private fun host(context: Context, role: EngineRole): EngineHost =
        hosts.getOrPut(role) {
            val app = context.applicationContext
            if (EngineConfig.IN_PROCESS) LocalEngineHost(app, role)
            else RemoteEngineHost(app, role, tuning, beforeOpen = if (role == EngineRole.BATCH) ({ stopLive() }) else ({}), settler = settler(app))
        }

    /** Settles the guard entries of the handles a host generation still held when it ended (see [EngineConnection]). */
    private fun settler(app: Context) = object : HandleSettler {
        override fun unloaded(guardModel: String, backend: String) {
            runCatching { Backends.guard(app).unloaded(guardModel, backend) }
        }

        // A strike for a GPU/NPU engine the host took down with it, and the resident note goes, so the next start does not count it again.
        override fun diedWhileLoaded(guardModel: String, backend: String, exit: BackendGuard.LastExit) {
            runCatching { Backends.guard(app).diedWhileLoaded(guardModel, backend, exit).forEach { Backends.log(it, warn = true) } }
        }
    }

    /**
     * `:live` before `:batch` (AD-01): runs before every `Open` in `:batch`, also when `:batch` is already bound and idle. The embedder stops
     * taking work and unloads without waiting for a running call, then the `:live` process is shut down and its death awaited (a kill after
     * `liveShutdownMs`). A cheap state check when `:live` is not bound. Blocks, so worker threads only; `MemoryService` still calls [suspend]
     * first and this is the safety net.
     */
    fun stopLive() {
        embedder?.suspendNow()
        (hosts[EngineRole.LIVE] as? RemoteEngineHost)?.stopForBatch()
    }

    /**
     * Part of `AppLog.clear()` in main: the hosts' own log files go too. Best effort and it never binds a host. A bound host is asked with
     * `EngineOp.ClearLogs`; the files of roles that are not bound (or did not answer) are deleted through [deleteFiles]. A `LocalEngineHost`
     * is never asked: its ClearLogs would run `AppLog.clear` in this very process again. A running host that lost its file re-opens a new
     * one on its next line.
     */
    fun clearHostLogs(deleteFiles: (EngineRole) -> Unit) {
        for (role in listOf(EngineRole.LIVE, EngineRole.BATCH)) {
            val asked = (hosts[role] as? RemoteEngineHost)?.let { runCatching { it.clearLogsIfBound() }.getOrDefault(false) } == true
            if (!asked) runCatching { deleteFiles(role) }
        }
    }

    fun embedder(context: Context): Embedder =
        embedder ?: synchronized(this) { embedder ?: Embedder(context.applicationContext, host = liveHost(context)).also { embedder = it } }

    /** Unloads the shared embedder (if it was created) while sessions are processed. Live cues and search skip it until [resume]. */
    suspend fun suspend() {
        embedder?.suspend()
        (hosts[EngineRole.LIVE] as? RemoteEngineHost)?.release()
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
