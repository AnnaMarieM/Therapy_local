package com.therapylocal.app.embed

import android.content.Context

/**
 * The process-wide [Embedder]. A recording outlives the screen that started it (see SessionController), so live cues and search share one
 * loaded model instead of each view model loading its own.
 */
object Engines {
    @Volatile
    private var embedder: Embedder? = null

    fun embedder(context: Context): Embedder =
        embedder ?: synchronized(this) { embedder ?: Embedder(context.applicationContext).also { embedder = it } }

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
