package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/** Thrown by a handler that saw Stop: the job goes back to pending without counting an attempt. */
class JobStopped : Exception()

/** A handler failure with a short [code] (stored, so never patient text). [permanent] fails the job without using its other attempts. */
class JobFailure(val code: String, val permanent: Boolean = false) : Exception(code)

/** What a handler gets besides its job: the shared models, the Stop check and a way to report progress. */
class JobContext(val pool: ModelPool, private val queue: JobQueue) {
    /** Call between steps; throws [JobStopped] once Stop was pressed. */
    fun checkStop() {
        if (queue.stopRequested) throw JobStopped()
    }

    fun progress(phase: JobPhase, step: Int, steps: Int) = queue.progress(StepProgress(phase, step, steps))
}

fun interface JobHandler {
    fun run(job: Job, context: JobContext)
}

/**
 * Runs claimable jobs one at a time until none is left, Stop is pressed or [canRun] holds every remaining kind back (a model missing,
 * a session recording). A running job always finishes or stops at a step boundary first. The [pool]'s models are closed when it returns.
 */
class JobRunner(
    private val queue: JobQueue,
    private val handlers: Map<String, JobHandler>,
    private val pool: ModelPool,
    private val canRun: (String) -> Boolean,
) {
    fun drain() {
        val context = JobContext(pool, queue)
        try {
            while (!queue.stopRequested) {
                val job = queue.claimNext { it in handlers && canRun(it) } ?: break
                // Between jobs only, and after the claim checked that the chosen models are ready.
                pool.refresh()
                run(job, context)
            }
        } finally {
            pool.close()
        }
    }

    /** True if a drain would find something to do now. */
    fun hasWork(): Boolean = !queue.stopRequested && queue.hasRunnable { it in handlers && canRun(it) }

    private fun run(job: Job, context: JobContext) {
        val c = LogCtx.NONE
        try {
            AppLog.i(TAG, "job_start", c, "job" to job.id, "kind" to job.kind, "attempt" to job.attempts + 1)
            handlers.getValue(job.kind).run(job, context)
            queue.complete(job)
        } catch (_: JobStopped) {
            queue.release(job)
        } catch (_: ModelSwitched) {
            // Picked up again with the new build once it is ready.
            queue.release(job)
        } catch (t: Throwable) {
            // The session was deleted while the job ran: there is nothing left to do, which is not a failure.
            if (!queue.repo.sessionExists(job.sessionId)) return queue.cancel(job)
            // Only a code or the exception type is kept: messages can quote patient text. An anonymous class has no simple name.
            val code = (t as? JobFailure)?.code ?: t.javaClass.simpleName.ifEmpty { "ERROR" }
            AppLog.w(TAG, "job_failed", c, "job" to job.id, "kind" to job.kind, "code" to code)
            queue.fail(job, code, (t as? JobFailure)?.permanent == true)
        }
    }

    companion object {
        const val TAG = "Jobs"

        /** Set while a session is recording: no new job is claimed then, so the models of a job and of the live session are never loaded together. */
        @Volatile
        var recordingActive = false
    }
}
