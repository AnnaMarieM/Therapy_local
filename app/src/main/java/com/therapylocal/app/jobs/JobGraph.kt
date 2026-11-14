package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus

/**
 * The job graph of a session, and the one place that adds its jobs to the queue:
 *
 *   transcribe(recording) -> translate(session) -> roles(session) -> window(session) -> extract(session, 1..n) -> commit(session) -> cleanup(recording)
 *
 * [TRANSLATE] puts the transcript into English for every job after it (docs/AD/AD-17.md); it does nothing for an English client or in the native
 * processing mode. A queue written before it existed has no translate job: those sessions are processed as before.
 *
 * [ROLES] guesses who is the therapist once the speakers are known. A queue written before it existed holds transcribe -> window without it: those
 * sessions are processed as before and get no role guess.
 *
 * The extracts and later jobs are only known once [WINDOW] has cut the transcript, so it adds them ([expand]). A client's sessions are
 * committed in order of their start (D4): commit(S) waits for every unfinished job but cleanup of the session before S, a session windowed
 * after the one that follows it makes that one's pending commit wait for its own, and a commit re-queues the commits of the client's later
 * sessions that were already committed ([recommit]), because their mentions may point at its facts.
 */
object JobGraph {
    const val TRANSCRIBE = "transcribe"
    const val TRANSLATE = "translate"
    const val ROLES = "roles"
    const val WINDOW = "window"
    const val EXTRACT = "extract"
    const val COMMIT = "commit"
    const val CLEANUP = "cleanup"

    /**
     * The last-session summary (docs/AD/AD-12.md): not part of the chain above, so a failed summary never blocks the facts. Queued behind the
     * window job of a new session (after the roles are known) and again, on its own, to rebuild (roles swapped, "regenerate").
     */
    const val SUMMARY = "summary"

    /**
     * Translates what is shown into the app language (docs/AD/AD-17.md): the facts and the summary of the session. Not part of the chain above, so
     * a failed one never blocks the facts. Queued at the end of the commit and of the summary job, and for every session after an app language switch.
     */
    const val LOCALIZE = "localize"

    /** After a recording has stopped and every chunk is saved. Returns the window job. */
    fun enqueueRecording(queue: JobQueue, session: String, recording: Long): Long {
        val transcribe = queue.enqueue(TRANSCRIBE, session, recording)
        val translate = queue.enqueue(TRANSLATE, session, recording, dependsOn = listOf(transcribe))
        val roles = queue.enqueue(ROLES, session, recording, dependsOn = listOf(translate))
        return queue.enqueue(WINDOW, session, recording, dependsOn = listOf(roles))
    }

    /** Queues the summary of [session] (once: an unfinished one is reused). [after] are jobs that must finish first. */
    fun enqueueSummary(queue: JobQueue, session: String, after: List<Long> = emptyList()): Long = queue.enqueue(SUMMARY, session, dependsOn = after)

    /** A session whose segments are already stored (the developer harness imports text transcripts): no audio, no cleanup. */
    fun enqueueTranscript(queue: JobQueue, session: String): Long {
        val translate = queue.enqueue(TRANSLATE, session)
        return queue.enqueue(WINDOW, session, dependsOn = listOf(translate))
    }

    /** Queues the translation of what is shown for [session] (once: an unfinished one is reused). [after] are jobs that must finish first. */
    fun enqueueLocalize(queue: JobQueue, session: String, after: List<Long> = emptyList()): Long = queue.enqueue(LOCALIZE, session, dependsOn = after)

    /**
     * At startup: a session's recording that no job refers to was cut off (process killed) before its jobs were queued. Any job counts, not
     * only transcribe: a done transcribe job is pruned while its cleanup still waits, and queuing the recording again then would race that cleanup.
     */
    fun recoverRecordings(queue: JobQueue, recordings: List<Pair<Long, String>>) {
        val queued = queue.repo.jobs().mapNotNull { it.recordingId }.toSet()
        for ((recording, session) in recordings) if (recording !in queued) enqueueRecording(queue, session, recording)
    }

    /**
     * Called by the [WINDOW] job once it has stored [windows] windows; [previousSession] and [nextSession] are the client's sessions before
     * and after this one.
     */
    fun expand(queue: JobQueue, window: Job, windows: Int, previousSession: String?, nextSession: String? = null) {
        val session = window.sessionId
        val extracts = (1..windows).map { queue.enqueue(EXTRACT, session, ref = "$it", dependsOn = listOf(window.id)) }
        val previous = previousSession?.let { p -> queue.unfinished(p).filter { it.kind != CLEANUP }.map { it.id } }.orEmpty()
        val commit = queue.enqueue(COMMIT, session, window.recordingId, dependsOn = listOf(window.id) + extracts + previous)
        window.recordingId?.let { queue.enqueue(CLEANUP, session, it, dependsOn = listOf(commit)) }
        // The next session was windowed first: its commit only waited for this window job, so it waits for this commit too. A running one
        // is committed again after this one ([recommit]).
        nextSession?.let { queue.unfinished(COMMIT, it) }?.takeIf { it.status != JobStatus.Running }?.let { queue.addDependency(it.id, commit) }
    }

    /** Called by a running [COMMIT] job: the [later] sessions (in order) are committed again, each after the one before it. */
    fun recommit(queue: JobQueue, commit: Job, later: List<String>) {
        var previous = commit.id
        for (session in later) previous = queue.enqueue(COMMIT, session, dependsOn = listOf(previous))
    }
}
