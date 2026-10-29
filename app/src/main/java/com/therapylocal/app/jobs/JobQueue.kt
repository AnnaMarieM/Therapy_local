package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobRepo
import com.therapylocal.app.data.JobStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the running job is doing, for the notification and the progress banner. */
enum class JobPhase { None, Transcribing, Reading, Checking }

data class StepProgress(val phase: JobPhase, val step: Int, val steps: Int)

/** The jobs of one session: [blocked] are pending behind a failed or cancelled job and will not run until it is retried. */
data class SessionProgress(
    val sessionId: String,
    val clientId: String,
    val startedAt: Long,
    val total: Int,
    val done: Int,
    val failed: Int,
    val blocked: Int,
    val running: Boolean,
) {
    val finished: Boolean get() = done == total
}

/** The queue as the UI and the notification see it. [sessions] are in the order their first job was queued. */
data class QueueSnapshot(
    val jobs: List<Job>,
    val sessions: List<SessionProgress>,
    val running: Job?,
    val progress: StepProgress?,
    val stopping: Boolean,
) {
    val failed: Int get() = jobs.count { it.status == JobStatus.Failed }
    val blocked: Int get() = sessions.sumOf { it.blocked }

    companion object {
        val EMPTY = QueueSnapshot(emptyList(), emptyList(), null, null, false)

        fun of(jobs: List<Job>, progress: StepProgress?, stopping: Boolean): QueueSnapshot {
            val blocked = blockedIds(jobs)
            val sessions = jobs.groupBy { it.sessionId }.map { (sid, list) ->
                SessionProgress(
                    sid, list.first().clientId, list.first().sessionStartedAt, list.size,
                    list.count { it.status == JobStatus.Done || it.status == JobStatus.Cancelled },
                    list.count { it.status == JobStatus.Failed }, list.count { it.id in blocked }, list.any { it.status == JobStatus.Running },
                )
            }
            val running = jobs.firstOrNull { it.status == JobStatus.Running }
            return QueueSnapshot(jobs, sessions, running, progress.takeIf { running != null }, stopping)
        }

        /** Pending jobs that wait, directly or through other pending jobs, on a failed or cancelled one. */
        private fun blockedIds(jobs: List<Job>): Set<Long> {
            val byId = jobs.associateBy { it.id }
            val memo = HashMap<Long, Boolean>()
            fun blocked(j: Job): Boolean = memo.getOrPut(j.id) {
                memo[j.id] = false // a dependency cycle never blocks
                j.status == JobStatus.Pending && j.dependsOn.any { d ->
                    val dep = byId[d] ?: return@any false
                    dep.status == JobStatus.Failed || dep.status == JobStatus.Cancelled || blocked(dep)
                }
            }
            return jobs.filter(::blocked).map { it.id }.toSet()
        }
    }
}

/**
 * The persistent job queue: pending jobs run oldest first, each only once every job it depends on is done. A job that fails goes back to
 * pending until it has used [Job.maxAttempts], then stays failed (its dependents stay pending, shown as blocked) until [retryFailed].
 * Pure logic over [JobRepo]; every change is published on [snapshot].
 */
class JobQueue(
    /** The database this queue writes to, so a holder can tell whether it is still the open one. */
    val repo: JobRepo,
    private val snapshotFlow: MutableStateFlow<QueueSnapshot> = MutableStateFlow(QueueSnapshot.EMPTY),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshot: StateFlow<QueueSnapshot> = snapshotFlow.asStateFlow()

    /** Set by Stop; the running handler checks it between steps. Cleared when a new drain starts. */
    @Volatile
    var stopRequested = false
        private set

    @Volatile
    private var progress: StepProgress? = null

    /**
     * Adds a pending job, or returns the id of the one of the same kind, session and [ref] that is not done yet (pending, running or failed).
     * Dependencies that no longer exist count as done.
     */
    fun enqueue(kind: String, session: String, recording: Long? = null, ref: String = "", dependsOn: List<Long> = emptyList(), maxAttempts: Int = MAX_ATTEMPTS): Long =
        change {
            val all = repo.jobs()
            all.firstOrNull { it.kind == kind && it.sessionId == session && it.ref == ref && !it.status.settled }?.id
                ?: repo.insertJob(kind, session, recording, ref, maxAttempts, dependsOn.filter { d -> all.any { it.id == d } }, clock())
        }

    /**
     * In one transaction: the oldest pending job whose dependencies are all done and whose kind [canRun] allows, marked running.
     * Null when nothing can run now.
     */
    fun claimNext(canRun: (String) -> Boolean): Job? = change {
        val all = repo.jobs()
        val byId = all.associateBy { it.id }
        all.firstOrNull { ready(it, byId) && canRun(it.kind) }?.let { j ->
            repo.updateJob(j.id, JobStatus.Running, j.attempts, null, clock())
            j.copy(status = JobStatus.Running, errorCode = null)
        }
    }

    /** True if [claimNext] would find a job now. Changes nothing. */
    fun hasRunnable(canRun: (String) -> Boolean): Boolean {
        val all = repo.jobs()
        val byId = all.associateBy { it.id }
        return all.any { ready(it, byId) && canRun(it.kind) }
    }

    fun hasPending(): Boolean = repo.jobs().any { it.status == JobStatus.Pending }

    fun complete(job: Job) = set(job, JobStatus.Done, job.attempts, null)

    /** Counts an attempt. Back to pending while attempts are left, unless [permanent]. [code] is a short error code, never raw error text. */
    fun fail(job: Job, code: String, permanent: Boolean = false) {
        val attempts = job.attempts + 1
        set(job, if (permanent || attempts >= job.maxAttempts) JobStatus.Failed else JobStatus.Pending, attempts, code)
    }

    /** Back to pending without counting an attempt: the job was stopped, or a precondition went away. */
    fun release(job: Job) = set(job, JobStatus.Pending, job.attempts, job.errorCode)

    /** The job's session was deleted while it ran: nothing to do, which is not a failure. */
    fun cancel(job: Job) = set(job, JobStatus.Cancelled, job.attempts, null)

    /**
     * At startup: a job still marked running was cut off (process killed, app locked) and goes back to pending without counting an attempt.
     * Finished jobs that no unfinished job depends on are pruned.
     */
    fun recoverStale() = change {
        val all = repo.jobs()
        for (j in all) if (j.status == JobStatus.Running) repo.updateJob(j.id, JobStatus.Pending, j.attempts, j.errorCode, clock())
        val needed = all.filter { !it.status.settled }.flatMap { it.dependsOn }.toSet()
        repo.deleteJobs(all.filter { it.status.settled && it.id !in needed }.map { it.id })
    }

    /** Failed jobs get all their attempts back. */
    fun retryFailed() = change {
        for (j in repo.jobs()) if (j.status == JobStatus.Failed) repo.updateJob(j.id, JobStatus.Pending, 0, null, clock())
    }

    /** Removes the session's jobs; a running one is left to finish (its handler then finds the session gone). */
    fun dropSession(session: String) = drop { it.sessionId == session }

    fun dropClient(client: String) = drop { it.clientId == client }

    fun requestStop() {
        stopRequested = true
        publish()
    }

    fun clearStop() {
        stopRequested = false
        publish()
    }

    /** Progress of the running job; null when it has none to report. */
    fun progress(p: StepProgress?) {
        progress = p
        snapshotFlow.value = snapshotFlow.value.copy(progress = p.takeIf { snapshotFlow.value.running != null })
    }

    fun publish() {
        snapshotFlow.value = QueueSnapshot.of(repo.jobs(), progress, stopRequested)
    }

    private fun ready(j: Job, byId: Map<Long, Job>) = j.status == JobStatus.Pending && j.dependsOn.all { (byId[it]?.status ?: JobStatus.Done) == JobStatus.Done }

    private fun drop(match: (Job) -> Boolean) = change {
        repo.deleteJobs(repo.jobs().filter { match(it) && it.status != JobStatus.Running }.map { it.id })
    }

    private fun set(job: Job, status: JobStatus, attempts: Int, code: String?) = change {
        if (status != JobStatus.Running) progress = null
        repo.updateJob(job.id, status, attempts, code, clock())
    }

    private fun <T> change(block: () -> T): T = repo.inTransaction(block).also { publish() }

    /** Nothing more will happen to the job by itself. */
    private val JobStatus.settled get() = this == JobStatus.Done || this == JobStatus.Cancelled

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
