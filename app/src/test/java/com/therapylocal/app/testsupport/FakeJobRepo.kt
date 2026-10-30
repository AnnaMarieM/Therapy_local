package com.therapylocal.app.testsupport

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobRepo
import com.therapylocal.app.data.JobStatus

/** In-memory [JobRepo] with the same visible behaviour as the SQL one: jobs of a deleted session are gone with it. */
class FakeJobRepo : JobRepo {
    private data class Row(val id: Long, val kind: String, val session: String, val recording: Long?, val ref: String, var status: JobStatus,
        var attempts: Int, val max: Int, var code: String?, val created: Long, val deps: MutableList<Long>)

    /** session id -> (client, started at) */
    val sessions = LinkedHashMap<String, Pair<String, Long>>()
    private val rows = LinkedHashMap<Long, Row>()
    private var nextId = 1L

    override fun <T> inTransaction(block: () -> T): T = block()

    override fun jobs(): List<Job> = rows.values.filter { it.session in sessions }.map { r ->
        val (client, started) = sessions.getValue(r.session)
        Job(r.id, r.kind, r.session, client, started, r.recording, r.ref, r.status, r.attempts, r.max, r.code, r.created, r.deps.filter { it in rows })
    }

    override fun insertJob(kind: String, sessionId: String, recordingId: Long?, ref: String, maxAttempts: Int, dependsOn: List<Long>, now: Long): Long {
        require(sessionId in sessions) { "foreign key" }
        val id = nextId++
        rows[id] = Row(id, kind, sessionId, recordingId, ref, JobStatus.Pending, 0, maxAttempts, null, now, dependsOn.toMutableList())
        return id
    }

    override fun updateJob(id: Long, status: JobStatus, attempts: Int, errorCode: String?, now: Long) {
        rows[id]?.let { it.status = status; it.attempts = attempts; it.code = errorCode }
    }

    override fun addDependency(jobId: Long, dependsOn: Long) {
        rows[jobId]?.deps?.let { if (dependsOn !in it) it += dependsOn }
    }

    override fun deleteJobs(ids: Collection<Long>) {
        ids.forEach { rows.remove(it) }
    }

    override fun sessionExists(id: String) = id in sessions

    /** Like ON DELETE CASCADE: the session's jobs go with it. */
    fun deleteSession(id: String) {
        sessions.remove(id)
        rows.values.removeAll { it.session == id }
    }
}
