package com.therapylocal.app.jobs

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated dispatchers for background model inference, embedding, and transcription.
 *
 * Compute threads are explicitly assigned [Process.THREAD_PRIORITY_BACKGROUND] (Linux nice 10).
 * This guarantees the Android UI thread and RenderThread have absolute scheduling priority,
 * preventing any skipped frames, input timeouts, or visual stuttering regardless of CPU load.
 */
object ModelDispatchers {
    private val poolSize = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))

    private const val TAG = "ModelDispatchers"

    /**
     * The priority is set on the worker thread itself, as the first thing it runs. A ThreadFactory runs on the thread that creates the
     * worker (possibly a UI coroutine), so setting it there would lower the submitter and leave the worker at normal priority.
     */
    internal fun threadFactory(setPriority: (Int) -> Unit = { Process.setThreadPriority(it) }): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread({
                setPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "model-compute-${n.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    val Compute: CoroutineDispatcher = Executors.newFixedThreadPool(poolSize, threadFactory()).asCoroutineDispatcher().also {
        runCatching {
            AppLog.i(TAG, "model_dispatcher_ready", LogCtx.NONE, "threads" to poolSize, "priority" to Process.THREAD_PRIORITY_BACKGROUND)
        }
    }
}
