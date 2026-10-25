package com.therapylocal.app.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault

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
    /** Recording whose audio still has to be transcribed first (made before setup finished); 0 = the transcript is already written. */
    val recordingId: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("client", client).put("file", file).put("date", date).put("status", status.name)
        .put("stage", stage).put("windows", windows).put("windowsDone", windowsDone).put("extracted", extracted).put("added", added)
        .put("error", error ?: JSONObject.NULL).put("startedAt", startedAt).put("finishedAt", finishedAt)
        .put("prefill", prefillTokPerSec).put("decode", decodeTokPerSec).put("recordingId", recordingId)

    companion object {
        fun fromJson(o: JSONObject) = SessionJob(
            o.getString("id"), o.getString("client"), o.getString("file"), o.optString("date"),
            // A job that was Running when the process died did not finish, so it goes back in the queue.
            JobStatus.valueOf(o.optString("status", "Queued")).let { if (it == JobStatus.Running) JobStatus.Queued else it },
            o.optString("stage"), o.optInt("windows"), o.optInt("windowsDone"), o.optInt("extracted"), o.optInt("added"),
            if (o.isNull("error")) null else o.optString("error"), o.optLong("startedAt"), o.optLong("finishedAt"),
            o.optDouble("prefill", 0.0), o.optDouble("decode", 0.0), o.optLong("recordingId", 0),
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
 * The job list is saved encrypted to the vault's queue file ([MemoryFiles.queue]) after every change, so a crash or an app restart resumes where it stopped.
 * While the vault is locked nothing can be read or written: saving is skipped and the saved list reads as empty.
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
        val savedList = if (resume) readSaved() else emptyList()
        // App mode has no fixed job list: whatever was queued (and not finished) before the process died simply continues.
        if (fresh.isEmpty()) {
            update { it.copy(jobs = savedList) }
            return
        }
        val saved = savedList.associateBy { it.id }
        update { it.copy(jobs = fresh.map { j -> saved[j.id]?.takeIf { s -> s.status == JobStatus.Done } ?: saved[j.id]?.takeIf { s -> s.status == JobStatus.Failed }?.copy(status = JobStatus.Queued, error = null) ?: j }) }
    }

    /** Shows the saved jobs (Running ones as Queued) to the UI before the runner has started, e.g. sessions waiting for setup. */
    fun restore() = load(emptyList(), resume = true)

    /** Adds a job at the end of the queue unless one with that id exists. Safe to call while the runner is working: it picks up new Queued jobs. */
    @Synchronized
    fun enqueue(job: SessionJob) {
        if (_state.value.jobs.any { it.id == job.id }) return
        update { it.copy(jobs = it.jobs + job) }
    }

    /** Drops one queued or finished job; a running one is left alone. */
    fun dropSession(id: String) = update { s -> s.copy(jobs = s.jobs.filterNot { it.id == id && it.status != JobStatus.Running }) }

    /** True if the saved queue (not the in-memory one) holds a job that still has to run. Used after unlock, before the runner has loaded anything. */
    fun hasSavedWork(): Boolean = readSaved().any { it.status == JobStatus.Queued }

    fun hasQueued(): Boolean = _state.value.jobs.any { it.status == JobStatus.Queued }

    /** Drops every job of [client] that is not running, for when the client is deleted. */
    fun dropClient(client: String) = update { s -> s.copy(jobs = s.jobs.filterNot { it.client == client && it.status != JobStatus.Running }, profiles = s.profiles - client) }

    fun retryFailed() = update { s -> s.copy(jobs = s.jobs.map { if (it.status == JobStatus.Failed || it.status == JobStatus.Cancelled) it.copy(status = JobStatus.Queued, error = null, stage = "") else it }) }

    private fun readSaved(): List<SessionJob> = try {
        JSONArray(file?.let { SecureFile.readText(it, Vault.key()) } ?: "[]").let { a -> (0 until a.length()).map { SessionJob.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        try {
            file?.let { SecureFile.writeText(it, JSONArray(_state.value.jobs.map { j -> j.toJson() }).toString(), Vault.key()) }
        } catch (_: Exception) {
        }
    }
}
