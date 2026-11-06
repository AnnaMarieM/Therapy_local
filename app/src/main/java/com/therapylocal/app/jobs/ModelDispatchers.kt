package com.therapylocal.app.jobs

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated dispatchers for background model inference, embedding, and transcription.
 *
 * Compute threads are explicitly assigned [Process.THREAD_PRIORITY_BACKGROUND] (Linux nice 10).
 * This guarantees the Android UI thread and RenderThread have absolute scheduling priority,
 * preventing any skipped frames, input timeouts, or visual stuttering regardless of CPU load.
 */
object ModelDispatchers {
    private val poolSize = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))

    val Compute: CoroutineDispatcher = Executors.newFixedThreadPool(poolSize) { runnable ->
        Thread(runnable, "model-compute").apply {
            isDaemon = true
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        }
    }.asCoroutineDispatcher()
}

