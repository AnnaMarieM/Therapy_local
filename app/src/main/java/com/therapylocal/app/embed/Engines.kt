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

    /** Proactively frees the embedder if idle when the system reports memory pressure. */
    fun onLowMemory() {
        embedder?.let { e ->
            if (!com.therapylocal.app.jobs.JobRunner.recordingActive) {
                e.close()
                com.therapylocal.app.log.AppLog.w("Memory", "proactive_embedder_unload", com.therapylocal.app.log.LogCtx.NONE)
            }
        }
        System.gc()
    }
}
