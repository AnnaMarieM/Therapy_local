package com.therapylocal.app.qaapi

import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.QueueSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** The job queue as `status` and `wait` report it. Counts and job kinds only, never session ids that carry names or any text. */
object QaQueueState {
    /**
     * Idle: nothing runs and nothing that could run is waiting. A pending job that waits on a failed one (blocked) does not count, or one
     * failure would make every wait time out; failed jobs are reported in [failed] instead.
     */
    fun idle(s: QueueSnapshot): Boolean = s.running == null && s.jobs.count { it.status == JobStatus.Pending } - s.blocked <= 0

    fun toJson(s: QueueSnapshot): JSONObject {
        val pending = s.jobs.count { it.status == JobStatus.Pending }
        val kinds = JSONArray(s.jobs.filter { it.status == JobStatus.Pending || it.status == JobStatus.Running }.map { it.kind }.distinct().sorted())
        return JSONObject().put("idle", idle(s)).put("total", s.jobs.size).put("pending", pending).put("blocked", s.blocked).put("failed", s.failed)
            .put("done", s.jobs.count { it.status == JobStatus.Done }).put("running", s.running?.kind ?: JSONObject.NULL).put("open_kinds", kinds)
            .put("stopping", s.stopping)
    }
}
