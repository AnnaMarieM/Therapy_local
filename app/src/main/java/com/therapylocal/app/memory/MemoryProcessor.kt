package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault

/**
 * What the app calls when a session has ended: stores the transcript (encrypted, in the vault's internal storage) and queues the session's
 * `process` job. Processing runs in [MemoryService] (foreground, survives leaving the app) and shows up in [Jobs.snapshot] under the session id.
 */
object MemoryProcessor {
    /** Queues [transcript] of [sessionId] (which must start with `<client>_`). Returns false for a blank transcript. Idempotent per session id. Needs the vault unlocked. */
    fun enqueueSession(context: Context, client: String, sessionId: String, date: String, transcript: String): Boolean {
        require(sessionId.startsWith("${client}_")) { "session id must start with the client id" }
        if (transcript.isBlank()) return false
        SecureFile.writeText(MemoryFiles.transcript(context, "$sessionId.md"), transcript, Vault.key())
        enqueue(context, client, sessionId, date, null)
        return true
    }

    /** Queues a recording made before setup finished: its audio is transcribed first (by the runner), then the session is processed as usual. Needs the vault unlocked. */
    fun enqueueRecording(context: Context, client: String, sessionId: String, date: String, recordingId: Long) {
        require(sessionId.startsWith("${client}_")) { "session id must start with the client id" }
        enqueue(context, client, sessionId, date, recordingId)
    }

    private fun enqueue(context: Context, client: String, sessionId: String, date: String, recordingId: Long?) {
        MindStore.get(context).createSession(sessionId, client, startedAt(date))
        Jobs.queue(context).enqueue(MemoryLab.KIND, sessionId, recordingId, ref = "$sessionId.md")
        startIfReady(context)
    }

    /** A session's start, for ordering a client's sessions: its date (yyyy-MM-dd) at midnight, or now when the date is unknown. */
    fun startedAt(date: String): Long =
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!.time }.getOrDefault(System.currentTimeMillis())

    /** The runner needs the vault key and the AI files; until both are there the jobs just wait in the queue. */
    private fun startIfReady(context: Context) {
        if (Vault.isUnlocked && ModelSetup.isReady(context)) MemoryService.start(context.applicationContext)
    }

    /** After an unlock, a finished download or a recording: continues a queue that still has pending jobs (the process was killed, the app locked, or the runner paused). */
    fun resumePending(context: Context) {
        if (Vault.isUnlocked && Jobs.queue(context).hasPending()) startIfReady(context)
    }
}
