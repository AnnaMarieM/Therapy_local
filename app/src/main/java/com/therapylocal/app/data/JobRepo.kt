package com.therapylocal.app.data

enum class JobStatus { Pending, Running, Done, Failed, Cancelled }

/** A queued unit of work on one session, with the client and session start it belongs to and the jobs it waits for. [errorCode] is a short code, never raw error text. */
data class Job(
    val id: Long,
    val kind: String,
    val sessionId: String,
    val clientId: String,
    val sessionStartedAt: Long,
    val recordingId: Long?,
    val ref: String,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val errorCode: String?,
    val createdAt: Long,
    val dependsOn: List<Long>,
)

/** Persistence of the job queue: plain reads and writes. The queue logic on top is [com.therapylocal.app.jobs.JobQueue]. */
interface JobRepo {
    fun <T> inTransaction(block: () -> T): T

    /** Every job, oldest first. */
    fun jobs(): List<Job>

    fun insertJob(kind: String, sessionId: String, recordingId: Long?, ref: String, maxAttempts: Int, dependsOn: List<Long>, now: Long): Long

    fun updateJob(id: Long, status: JobStatus, attempts: Int, errorCode: String?, now: Long)

    fun deleteJobs(ids: Collection<Long>)

    fun sessionExists(id: String): Boolean
}
