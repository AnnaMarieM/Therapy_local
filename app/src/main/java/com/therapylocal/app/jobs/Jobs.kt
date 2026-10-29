package com.therapylocal.app.jobs

import android.content.Context
import com.therapylocal.app.data.MindStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The process-wide job queue, and its [snapshot] for the UI (the same flow even if the database is reopened after an erase). */
object Jobs {
    private val flow = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshot: StateFlow<QueueSnapshot> = flow.asStateFlow()

    @Volatile
    private var queue: JobQueue? = null

    /**
     * The queue on the open database (needs the vault unlocked). Its first use in a process is startup, so stale running jobs are recovered
     * then, and recordings whose jobs were never queued (the process died first) get them.
     */
    fun queue(context: Context): JobQueue {
        val store = MindStore.get(context)
        queue?.takeIf { it.repo === store }?.let { return it }
        return synchronized(this) {
            queue?.takeIf { it.repo === store } ?: JobQueue(store, flow).also { q ->
                q.recoverStale()
                JobGraph.recoverRecordings(q, store.recordings().mapNotNull { r -> r.sessionId?.let { r.id to it } })
                queue = q
            }
        }
    }

    /** Stop for whatever is running, without opening the database (it may already be locked or closing). */
    fun stopRunning() {
        queue?.requestStop()
    }
}
