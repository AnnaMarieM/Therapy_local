package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.perf.PerfStats

/** Thrown by a handler that saw Stop: the job goes back to pending without counting an attempt. */
class JobStopped : Exception()

/** A handler failure with a short [code] (stored, so never patient text). [permanent] fails the job without using its other attempts. */
class JobFailure(val code: String, val permanent: Boolean = false) : Exception(code) {
    companion object {
        /** The model keeps getting killed for memory even on the CPU: no retry helps, the user should pick the smaller build. */
        const val MODEL_TOO_LARGE = "model_too_large_for_device"

        fun modelTooLarge() = JobFailure(MODEL_TOO_LARGE, permanent = true)
    }
}

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

/** How a [JobRunner.drain] ended. */
sealed interface DrainResult {
    /** Nothing more to claim, or Stop was pressed. */
    object Finished : DrainResult

    /** No engine host can serve now (a restart storm, bind failures): the job went back to pending without an attempt and the drain stopped. Try again in [retryInMs]. */
    data class Deferred(val retryInMs: Long) : DrainResult
}

/**
 * Runs claimable jobs one at a time until none is left, Stop is pressed or [canRun] holds every remaining kind back (a model missing,
 * a session recording). A running job always finishes or stops at a step boundary first. The [pool]'s models are closed when it returns.
 */
class JobRunner(
    private val queue: JobQueue,
    private val handlers: Map<String, JobHandler>,
    private val pool: ModelPool,
    /** All jobs of a session are done (for the performance history). */
    private val onSessionDone: (String) -> Unit = {},
    private val canRun: (String) -> Boolean,
) {
    fun drain(): DrainResult {
        val context = JobContext(pool, queue)
        try {
            while (!queue.stopRequested) {
                val job = queue.claimNext { it in handlers && canRun(it) } ?: break
                // Between jobs only, and after the claim checked that the chosen models are ready.
                pool.refresh(job.kind)
                // A released job would be claimed again at once, a hot loop: the drain stops and the caller schedules the retry.
                run(job, context)?.let { return DrainResult.Deferred(it) }
            }
            return DrainResult.Finished
        } finally {
            pool.close()
        }
    }

    /** True if a drain would find something to do now. */
    fun hasWork(): Boolean = !queue.stopRequested && queue.hasRunnable { it in handlers && canRun(it) }

    /** Null when the job was settled (done, failed, released); the retry delay when no engine host could serve it. */
    private fun run(job: Job, context: JobContext): Long? {
        val c = LogCtx.NONE
        try {
            AppLog.i(TAG, "job_start", c, "job" to job.id, "kind" to job.kind, "attempt" to job.attempts + 1)
            val t0 = System.nanoTime()
            PerfStats.jobStarted(job.sessionId, job.kind)
            try {
                handlers.getValue(job.kind).run(job, context)
            } finally {
                PerfStats.jobEnded(job.sessionId, (System.nanoTime() - t0) / 1_000_000)
            }
            queue.complete(job)
            if (queue.snapshot.value.sessions.firstOrNull { it.sessionId == job.sessionId }?.finished == true) onSessionDone(job.sessionId)
        } catch (_: JobStopped) {
            queue.release(job)
        } catch (_: ModelSwitched) {
            // Picked up again with the new build once it is ready.
            queue.release(job)
        } catch (u: com.therapylocal.app.engine.EngineUnavailable) {
            // Nothing was wrong with the job or the model: no attempt is counted, and the job, its chunks and its audio are kept.
            queue.release(job)
            AppLog.w(TAG, "job_deferred", c, "job" to job.id, "kind" to job.kind, "code" to "EngineUnavailable", "why" to u.why, "role" to u.role.proc, "retry_in_ms" to u.retryInMs)
            return u.retryInMs
        } catch (t: Throwable) {
            // The session was deleted while the job ran: there is nothing left to do, which is not a failure.
            if (!queue.repo.sessionExists(job.sessionId)) { queue.cancel(job); return null }
            // Only a code or the exception type is kept: messages can quote patient text. An anonymous class has no simple name.
            val code = (t as? JobFailure)?.code ?: ((t as? com.therapylocal.app.engine.NamedError)?.typeName ?: t.javaClass.simpleName).ifEmpty { "ERROR" }
            // Types and code locations, never messages: enough to tell a backend failure from a parse error in field logs.
            AppLog.w(
                TAG, "job_failed", c, "job" to job.id, "kind" to job.kind, "code" to code, "attempt" to job.attempts + 1,
                "error" to com.therapylocal.app.models.BackendLadder.fullName(t), "cause_chain" to com.therapylocal.app.models.BackendLadder.causeChain(t, messages = false),
                "frames" to com.therapylocal.app.models.BackendLadder.frames(t), "is_oom" to (t is OutOfMemoryError),
            )
            queue.fail(job, code, (t as? JobFailure)?.permanent == true)
        }
        return null
    }

    companion object {
        const val TAG = "Jobs"

        /** Set while a session is recording: no new job is claimed then, so the models of a job and of the live session are never loaded together. */
        @Volatile
        var recordingActive = false
    }
}
