package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job

/**
 * The job graph of a session, and the one place that adds its jobs to the queue:
 *
 *   transcribe(recording) -> window(session) -> extract(session, 1..n) -> commit(session) -> cleanup(recording)
 *
 * The extracts and later jobs are only known once [WINDOW] has cut the transcript, so it adds them ([expand]). A client's sessions are
 * committed in order of their start (D4): commit(S) also waits for the unfinished commit of the session before S, and a commit re-queues
 * the commits of the client's later sessions that were already committed ([recommit]), because their mentions may point at its facts.
 */
object JobGraph {
    const val TRANSCRIBE = "transcribe"
    const val WINDOW = "window"
    const val EXTRACT = "extract"
    const val COMMIT = "commit"
    const val CLEANUP = "cleanup"

    /** After a recording has stopped and every chunk is saved. Returns the window job. */
    fun enqueueRecording(queue: JobQueue, session: String, recording: Long): Long {
        val transcribe = queue.enqueue(TRANSCRIBE, session, recording)
        return queue.enqueue(WINDOW, session, recording, dependsOn = listOf(transcribe))
    }

    /** A session whose segments are already stored (the developer harness imports text transcripts): no audio, no cleanup. */
    fun enqueueTranscript(queue: JobQueue, session: String): Long = queue.enqueue(WINDOW, session)

    /**
     * At startup: a session's recording that no job refers to was cut off (process killed) before its jobs were queued. Any job counts, not
     * only transcribe: a done transcribe job is pruned while its cleanup still waits, and queuing the recording again then would race that cleanup.
     */
    fun recoverRecordings(queue: JobQueue, recordings: List<Pair<Long, String>>) {
        val queued = queue.repo.jobs().mapNotNull { it.recordingId }.toSet()
        for ((recording, session) in recordings) if (recording !in queued) enqueueRecording(queue, session, recording)
    }

    /** Called by the [WINDOW] job once it has stored [windows] windows; [previousSession] is the client's session before this one. */
    fun expand(queue: JobQueue, window: Job, windows: Int, previousSession: String?) {
        val session = window.sessionId
        val extracts = (1..windows).map { queue.enqueue(EXTRACT, session, ref = "$it", dependsOn = listOf(window.id)) }
        val previousCommit = previousSession?.let { queue.unfinished(COMMIT, it)?.id }
        val commit = queue.enqueue(COMMIT, session, window.recordingId, dependsOn = listOf(window.id) + extracts + listOfNotNull(previousCommit))
        window.recordingId?.let { queue.enqueue(CLEANUP, session, it, dependsOn = listOf(commit)) }
    }

    /** Called by a running [COMMIT] job: the [later] sessions (in order) are committed again, each after the one before it. */
    fun recommit(queue: JobQueue, commit: Job, later: List<String>) {
        var previous = commit.id
        for (session in later) previous = queue.enqueue(COMMIT, session, dependsOn = listOf(previous))
    }
}
