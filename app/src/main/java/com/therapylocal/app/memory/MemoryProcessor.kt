package com.therapylocal.app.memory

import android.content.Context
import java.io.File

/**
 * What the app calls when a session has ended: stores the transcript and queues it for fact extraction and review.
 * Processing runs in [MemoryService] (foreground, survives leaving the app) and shows up in [MemoryQueue.state] under the session id.
 */
object MemoryProcessor {
    /** Client ids are lowercase slugs without underscores, because the queue derives the client from the session id's prefix. */
    fun clientId(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "client" }

    /** Session ids are `<client>_<yyyymmdd-hhmmss>` unless the caller has its own; the client is always the part before the first underscore. */
    fun sessionId(client: String, startedAtMs: Long): String =
        "${client}_" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAtMs))

    /** Queues [transcript] of [sessionId] (which must start with `<client>_`). Returns false for a blank transcript. Idempotent per session id. */
    fun enqueueSession(context: Context, client: String, sessionId: String, date: String, transcript: String): Boolean {
        require(sessionId.startsWith("${client}_")) { "session id must start with the client id" }
        if (transcript.isBlank()) return false
        val root = File(context.getExternalFilesDir(null), "memory")
        File(root, "in").apply { mkdirs() }.resolve("$sessionId.md").writeText(transcript)
        MemoryQueue.bind(File(File(root, "out").apply { mkdirs() }, "queue.json"))
        MemoryQueue.enqueue(SessionJob(sessionId, client, "$sessionId.md", date))
        MemoryService.start(context.applicationContext)
        return true
    }
}
