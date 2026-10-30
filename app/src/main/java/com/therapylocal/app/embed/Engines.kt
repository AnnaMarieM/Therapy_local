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
}
