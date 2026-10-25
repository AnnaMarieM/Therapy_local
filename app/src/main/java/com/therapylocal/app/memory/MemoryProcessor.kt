package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault

/**
 * What the app calls when a session has ended: stores the transcript (encrypted, in the vault's internal storage) and queues it for fact extraction and review.
 * Processing runs in [MemoryService] (foreground, survives leaving the app) and shows up in [MemoryQueue.state] under the session id.
 */
object MemoryProcessor {
    /** Client ids are lowercase slugs without underscores, because the queue derives the client from the session id's prefix. */
    fun clientId(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "client" }

    /** Session ids are `<client>_<yyyymmdd-hhmmss>` unless the caller has its own; the client is always the part before the first underscore. */
    fun sessionId(client: String, startedAtMs: Long): String =
        "${client}_" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAtMs))

    /** Queues [transcript] of [sessionId] (which must start with `<client>_`). Returns false for a blank transcript. Idempotent per session id. Needs the vault unlocked. */
    fun enqueueSession(context: Context, client: String, sessionId: String, date: String, transcript: String): Boolean {
        require(sessionId.startsWith("${client}_")) { "session id must start with the client id" }
        if (transcript.isBlank()) return false
        SecureFile.writeText(MemoryFiles.transcript(context, "$sessionId.md"), transcript, Vault.key())
        MemoryQueue.bind(MemoryFiles.queue(context))
        MemoryQueue.enqueue(SessionJob(sessionId, client, "$sessionId.md", date))
        startIfReady(context)
        return true
    }

    /** Queues a recording made before setup finished: its audio is transcribed first (by the runner), then the session is processed as usual. Needs the vault unlocked. */
    fun enqueueRecording(context: Context, client: String, sessionId: String, date: String, recordingId: Long) {
        require(sessionId.startsWith("${client}_")) { "session id must start with the client id" }
        MemoryQueue.bind(MemoryFiles.queue(context))
        MemoryQueue.enqueue(SessionJob(sessionId, client, "$sessionId.md", date, recordingId = recordingId))
        startIfReady(context)
    }

    /** The runner needs the vault key and the AI files; until both are there the jobs just wait in the queue. */
    private fun startIfReady(context: Context) {
        if (Vault.isUnlocked && ModelSetup.isReady(context)) MemoryService.start(context.applicationContext)
    }

    /** After an unlock: continues a queue that was left unfinished (the process was killed or the app locked). Starts [MemoryService] only if a saved job is Queued (a Running one was saved as Queued on read). */
    fun resumePending(context: Context) {
        if (!Vault.isUnlocked) return
        MemoryQueue.bind(MemoryFiles.queue(context))
        if (MemoryQueue.state.value.jobs.isEmpty()) MemoryQueue.restore()
        if (MemoryQueue.hasSavedWork() || MemoryQueue.hasQueued()) startIfReady(context)
    }
}
