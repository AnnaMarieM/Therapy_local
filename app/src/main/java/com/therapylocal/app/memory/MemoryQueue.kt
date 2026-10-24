package com.therapylocal.app.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class JobStatus { Queued, Running, Done, Failed, Cancelled }

/** One session transcript waiting to be turned into profile facts. Sessions of one client must run in date order, because each merges into the profile the last one left. */
data class SessionJob(
    val id: String,
    val client: String,
    val file: String,
    val date: String,
    val status: JobStatus = JobStatus.Queued,
    val stage: String = "",
    val windows: Int = 0,
    val windowsDone: Int = 0,
    val extracted: Int = 0,
    val added: Int = 0,
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val prefillTokPerSec: Double = 0.0,
    val decodeTokPerSec: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("client", client).put("file", file).put("date", date).put("status", status.name)
        .put("stage", stage).put("windows", windows).put("windowsDone", windowsDone).put("extracted", extracted).put("added", added)
        .put("error", error ?: JSONObject.NULL).put("startedAt", startedAt).put("finishedAt", finishedAt)
        .put("prefill", prefillTokPerSec).put("decode", decodeTokPerSec)

    companion object {
        fun fromJson(o: JSONObject) = SessionJob(
            o.getString("id"), o.getString("client"), o.getString("file"), o.optString("date"),
            // A job that was Running when the process died did not finish, so it goes back in the queue.
            JobStatus.valueOf(o.optString("status", "Queued")).let { if (it == JobStatus.Running) JobStatus.Queued else it },
            o.optString("stage"), o.optInt("windows"), o.optInt("windowsDone"), o.optInt("extracted"), o.optInt("added"),
            if (o.isNull("error")) null else o.optString("error"), o.optLong("startedAt"), o.optLong("finishedAt"),
            o.optDouble("prefill", 0.0), o.optDouble("decode", 0.0),
        )
    }
}

data class QueueState(
    val running: Boolean = false,
    val backend: String = "-",
    val message: String = "Idle",
    val jobs: List<SessionJob> = emptyList(),
    val profiles: Map<String, List<Fact>> = emptyMap(),
) {
    val clients: List<String> get() = jobs.map { it.client }.distinct()
}

/**
 * Process-wide state of the memory pipeline, observed by the UI. The runner ([MemoryLab]) is the only writer.
 * The job list is saved to `out/queue.json` after every change, so a crash or an app restart resumes where it stopped.
 */
object MemoryQueue {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /** Set by the UI's Stop button; the runner checks it between LLM calls (a call in flight finishes first). */
    @Volatile
    var cancelRequested = false

    private var file: File? = null

    fun bind(queueFile: File) {
        file = queueFile
    }

    fun update(f: (QueueState) -> QueueState) {
        _state.update(f)
        persist()
    }

    fun updateJob(id: String, f: (SessionJob) -> SessionJob) = update { s -> s.copy(jobs = s.jobs.map { if (it.id == id) f(it) else it }) }

    /** Replaces the job list. With [resume], jobs already Done in the saved queue keep their status and are skipped. */
    fun load(fresh: List<SessionJob>, resume: Boolean) {
        val saved = if (resume) readSaved().associateBy { it.id } else emptyMap()
        update { it.copy(jobs = fresh.map { j -> saved[j.id]?.takeIf { s -> s.status == JobStatus.Done } ?: saved[j.id]?.takeIf { s -> s.status == JobStatus.Failed }?.copy(status = JobStatus.Queued, error = null) ?: j }) }
    }

    /** Drops a deleted client's jobs and profile from the state (and the saved queue). */
    fun removeClient(client: String) = update { s -> s.copy(jobs = s.jobs.filter { it.client != client }, profiles = s.profiles - client) }

    fun retryFailed() = update { s -> s.copy(jobs = s.jobs.map { if (it.status == JobStatus.Failed || it.status == JobStatus.Cancelled) it.copy(status = JobStatus.Queued, error = null, stage = "") else it }) }

    private fun readSaved(): List<SessionJob> = try {
        JSONArray(file?.takeIf { it.exists() }?.readText() ?: "[]").let { a -> (0 until a.length()).map { SessionJob.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        try {
            file?.writeText(JSONArray(_state.value.jobs.map { it.toJson() }).toString())
        } catch (_: Exception) {
        }
    }
}
