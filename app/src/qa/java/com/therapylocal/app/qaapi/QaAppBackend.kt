package com.therapylocal.app.qaapi

import android.content.Context
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.config.SummaryFormatSetting
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMaps
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.DevHarness
import com.therapylocal.app.memory.DevResults
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.data.SessionSummaryRepository
import com.therapylocal.app.vault.Vault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The commands on the real app. Each one calls what the UI calls (Vault, MindStore, ClientRepository, SessionSummaryRepository, the job queue, DevResults):
 * none has behaviour of its own (AD-16 point 4). Logs carry counts and codes only, never names, text or paths.
 */
class QaAppBackend(private val context: Context) : QaBackend {
    private val app = context.applicationContext
    override val applicationId: String = BuildConfig.APPLICATION_ID
    override val filesRoot: File get() = app.getExternalFilesDir(null)!!

    override fun vaultState(): VaultState = when {
        !Vault.isSetUp(app) -> VaultState.NOT_SET_UP
        Vault.isUnlocked -> VaultState.UNLOCKED
        else -> VaultState.LOCKED
    }

    override fun status(): JSONObject {
        val state = vaultState()
        val progress = ModelSetup.progress.value
        val models = JSONArray(ModelSetup.required(app).map { JSONObject().put("id", it.id).put("ready", it.isReady(app)).put("missing_bytes", it.missingBytes(app)) })
        return JSONObject()
            .put("build", JSONObject().put("application_id", applicationId).put("version", BuildConfig.VERSION_NAME).put("commit", BuildConfig.GIT_COMMIT).put("build_type", BuildConfig.BUILD_TYPE))
            .put("vault", JSONObject().put("state", state.name.lowercase()))
            .put("models", JSONObject().put("ready", ModelSetup.isReady(app)).put("setup_state", progress.state.name.lowercase())
                .put("done_bytes", progress.doneBytes).put("total_bytes", progress.totalBytes).put("files", models))
            .put("jobs", if (state == VaultState.UNLOCKED) { Jobs.queue(app); QaQueueState.toJson(Jobs.snapshot.value) } else JSONObject.NULL)
    }

    override fun setup(password: String) = Vault.create(app, password.toCharArray())

    override fun unlock(password: String): Boolean = Vault.unlock(app, password.toCharArray())

    override fun seedClients(clients: List<QaClient>): JSONObject {
        val store = MindStore.get(app)
        val created = JSONArray()
        val existing = JSONArray()
        for (c in clients) {
            if (store.clients().any { it.id == c.id }) existing.put(c.id) else { store.createClient(c.id, c.name, c.language); created.put(c.id) }
        }
        AppLog.i(TAG, "seed_clients", LogCtx.NONE, "created" to created.length(), "existing" to existing.length())
        return JSONObject().put("created", created).put("existing", existing).put("clients", store.clients().size)
    }

    override fun importSession(client: String, file: File, session: String?, date: String?, language: String?): JSONObject {
        val store = MindStore.get(app)
        val row = clientRow(store, client)
        language?.let { store.setClientLanguage(row.id, it) }
        val sid = session ?: "${row.id}_${file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "-")}"
        val owner = store.clientOfSession(sid)
        if (owner.isNotEmpty() && owner != row.id) throw QaException(QaCode.BAD_REQUEST, "that session id belongs to another client")
        val startedAt = date?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)!!.time }.getOrNull() } ?: System.currentTimeMillis()
        store.createSession(sid, row.id, startedAt)
        val already = store.segments(sid).isNotEmpty() || store.recordings().any { it.sessionId == sid }
        if (!already) try {
            DevHarness.seedAudio(app, store, Jobs.queue(app), sid, file)
        } catch (e: IllegalArgumentException) {
            throw QaException(QaCode.BAD_REQUEST, "not a readable WAV file") // TestAudio.load's message names the file, nothing else
        }
        val started = startRunner()
        AppLog.i(TAG, "import_session", LogCtx.NONE, "already" to already, "bytes" to file.length(), "runner" to started)
        return JSONObject().put("client", row.id).put("session", sid).put("already_imported", already).put("runner_requested", started).put("models_ready", ModelSetup.isReady(app))
    }

    override fun importTranscript(client: String, session: String, startedAt: Long?, language: String?, segments: List<QaSegment>, speakerMap: JSONObject?, process: Boolean): JSONObject {
        val store = MindStore.get(app)
        val row = clientRow(store, client)
        language?.let { store.setClientLanguage(row.id, it) }
        val owner = store.clientOfSession(session)
        if (owner.isNotEmpty() && owner != row.id) throw QaException(QaCode.BAD_REQUEST, "that session id belongs to another client")
        store.createSession(session, row.id, startedAt ?: System.currentTimeMillis())
        val already = store.segments(session).isNotEmpty()
        if (!already) {
            store.appendTranscript(session, segments.mapIndexed { i, g -> SegmentRow(i + 1, g.startMs, g.endMs, g.text, g.speaker) })
            speakerMap?.let { store.appendSpeakerMap(session, it.toString(), "import", 1.0) }
            if (process) JobGraph.enqueueTranscript(Jobs.queue(app), session)
        }
        val started = if (!already && process) startRunner() else false
        AppLog.i(TAG, "import_transcript", LogCtx.NONE, "already" to already, "segments" to segments.size, "map" to (speakerMap != null), "process" to process, "runner" to started)
        return JSONObject().put("client", row.id).put("session", session).put("already_imported", already).put("segments", segments.size).put("runner_requested", started)
    }

    private fun clientRow(store: MindStore, client: String) = store.clients().firstOrNull { it.id == client } ?: store.clients().firstOrNull { it.name.equals(client, ignoreCase = true) }
        ?: throw QaException(QaCode.NOT_FOUND, "no such client (seed_clients first)")

    override fun exportData(client: String?, session: String?): JSONObject {
        val store = MindStore.get(app)
        val r = results(client, session, null)
        val factsBySession = HashMap<String, JSONArray>()
        val facts = r.getJSONArray("facts")
        for (i in 0 until facts.length()) facts.getJSONObject(i).let { factsBySession.getOrPut(it.getString("session")) { JSONArray() }.put(it) }
        val sessions = JSONArray()
        val rs = r.getJSONArray("sessions")
        for (i in 0 until rs.length()) {
            val s = rs.getJSONObject(i)
            val sid = s.getString("session")
            val cid = s.getString("client")
            val row = store.sessions(cid).first { it.id == sid }
            sessions.put(JSONObject().put("client", cid).put("session", sid).put("started_at", row.startedAt)
                .put("speaker_map", s.opt("speakerMap")?.takeIf { it is JSONObject }?.let { (it as JSONObject).optJSONObject("map") } ?: JSONObject.NULL)
                .put("segments", s.getJSONArray("segments")).put("speaker_maps", s.getJSONArray("speakerMaps")).put("summary", s.getJSONObject("summary"))
                .put("facts", factsBySession[sid] ?: JSONArray()))
        }
        val clients = JSONArray(store.clients().filter { client == null || it.id == client }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("lang", it.language).put("first_name", it.firstName).put("last_name", it.lastName).put("created_at", it.createdAt)
        })
        AppLog.i(TAG, "export", LogCtx.NONE, "clients" to clients.length(), "sessions" to sessions.length())
        // Re-importable with import_manifest: clients and sessions (segments, current speaker map). Not exported: audio, and the facts, summaries and
        // windows are derived (kept here for reading; an import queues the pipeline, which makes them again).
        return JSONObject().put("format", "therapy-qa-manifest").put("format_version", 1).put("summary_format", SummaryFormatSetting.get(app)).put("clients", clients).put("sessions", sessions)
    }

    private fun requireIdle() {
        Jobs.queue(app)
        if (!QaQueueState.idle(Jobs.snapshot.value)) throw QaException(QaCode.BUSY, "the job queue is working: wait for it first (one model at a time)")
    }

    override fun engineCall(op: String, args: JSONObject, file: File?, choice: BackendChoice): JSONObject {
        requireIdle()
        val e = QaEngines(app)
        return try {
            when (op) {
                "transcribe" -> e.transcribe(file!!, QaArgs.optString(args, "language") ?: "en", args.optDouble("start_s", 0.0), if (args.has("dur_s")) args.optDouble("dur_s") else null, choice)
                "diarize" -> e.diarize(file!!, args.optInt("speakers", 2), choice)
                "embed" -> e.embed(args.optJSONArray("texts")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: listOf(args.getString("text")), args.optBoolean("query", false), args.optBoolean("vectors", false), choice)
                "ask" -> e.ask(
                    args.optString("system", ""), args.getString("user"), args.opt("schema")?.takeIf { it != JSONObject.NULL }?.toString(),
                    args.optInt("max_tokens", 512).coerceIn(1, 4096), args.optDouble("temperature", 0.0), args.optInt("top_k", 1), choice,
                )
                else -> throw QaException(QaCode.UNKNOWN_COMMAND, "unknown op")
            }
        } catch (t: QaException) { throw t } catch (t: Throwable) {
            // The class and the engine's own failure code only: an engine message can quote the prompt.
            AppLog.w(TAG, "engine_call_failed", LogCtx.NONE, "op" to op, "type" to t.javaClass.simpleName)
            throw QaException(QaCode.FAILED, "$op failed: ${t.javaClass.simpleName}")
        }
    }

    private val jobStart = HashMap<Long, Long>()

    override fun runJob(kind: String, session: String, jobId: Long?, choice: BackendChoice, timeoutMs: Long): JSONObject {
        val store = MindStore.get(app)
        if (!store.sessionExists(session)) throw QaException(QaCode.NOT_FOUND, "no such session")
        val queue = Jobs.queue(app)
        val rec = if (kind == JobGraph.TRANSCRIBE) store.recordings().firstOrNull { it.sessionId == session }?.id ?: throw QaException(QaCode.NOT_FOUND, "the session has no recording to transcribe") else null
        val id = jobId ?: queue.enqueue(kind, session, rec).also { jobStart[it] = System.currentTimeMillis() }
        val t0 = jobStart.getOrPut(id) { System.currentTimeMillis() }
        // The override holds while this call waits; engines the runner loads in that time go through the same ladder (AD-08, AD-09) with the asked accelerator first.
        val job = choice.applied {
            startRunner()
            val end = System.currentTimeMillis() + timeoutMs
            var j = queue.openJobs().firstOrNull { it.id == id }
            while (j != null && System.currentTimeMillis() < end) { Thread.sleep(250); j = queue.openJobs().firstOrNull { it.id == id } }
            j
        }
        val settled = job == null || job.status == JobStatus.Failed
        val now = Jobs.snapshot.value.jobs.firstOrNull { it.id == id }
        AppLog.i(TAG, "run_job", LogCtx.NONE, "kind" to kind, "settled" to settled, "backend" to choice.backend)
        return JSONObject().put("job_id", id).put("kind", kind).put("session", session).put("settled", settled)
            .put("status", (now?.status ?: JobStatus.Done).name.lowercase()).put("attempts", now?.attempts ?: JSONObject.NULL).put("error_code", now?.errorCode ?: JSONObject.NULL)
            .put("dur_ms", System.currentTimeMillis() - t0).put("backend_requested", choice.backend)
            .put("backend_used", JSONObject.NULL).put("note", "the backend a job's models loaded on is in the log (evt=backend_proven, tag TL); run status or wait to follow the chain it queued")
    }

    /** Asks for the queue runner. The service start can be refused while the app is in the background; the host client brings the app forward first. */
    private fun startRunner(): Boolean = try { MemoryService.resumePending(app); true } catch (_: Exception) { false }

    override fun waitIdle(timeoutMs: Long): JSONObject {
        Jobs.queue(app)
        val t0 = System.currentTimeMillis()
        var idleSince = -1L
        var lastKick = 0L
        while (true) {
            val snap = Jobs.snapshot.value
            val now = System.currentTimeMillis()
            if (QaQueueState.idle(snap)) {
                if (idleSince < 0) idleSince = now
                if (now - idleSince >= SETTLE_MS) break // idle twice in a row: no job was between two steps
            } else {
                idleSince = -1
                if (snap.running == null && now - lastKick > 5000) { startRunner(); lastKick = now }
            }
            if (now - t0 >= timeoutMs) break
            Thread.sleep(250)
        }
        val snap = Jobs.snapshot.value
        val idle = QaQueueState.idle(snap)
        val reason = when {
            idle -> null
            !ModelSetup.isReady(app) -> "models_not_ready"
            snap.running == null -> "runner_not_running"
            else -> "running"
        }
        return QaQueueState.toJson(snap).put("timed_out", !idle).put("waited_ms", System.currentTimeMillis() - t0).put("reason", reason ?: JSONObject.NULL)
    }

    override fun results(client: String?, session: String?, queries: JSONArray?): JSONObject {
        val store = MindStore.get(app)
        val doc = DevResults.build(app, queries)
        val summaries = SessionSummaryRepository.create(app)
        val format = SummaryFormatSetting.get(app)
        val sessions = JSONArray()
        val all = doc.getJSONArray("sessions")
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            val sid = s.getString("session")
            if ((client != null && s.getString("client") != client) || (session != null && sid != session)) continue
            val current = store.currentSpeakerMap(sid)?.let { SpeakerMaps.decode(it.mapJson) }.orEmpty()
            val segs = s.getJSONArray("segments")
            for (k in 0 until segs.length()) {
                val g = segs.getJSONObject(k)
                g.put("role", if (g.isNull("speaker")) JSONObject.NULL else current[g.getString("speaker")]?.json ?: JSONObject.NULL)
            }
            s.put("speakerMaps", JSONArray(store.speakerMaps(sid).map {
                JSONObject().put("version", it.version).put("map", runCatching { JSONObject(it.mapJson) }.getOrDefault(JSONObject())).put("source", it.source)
                    .put("confidence", it.confidence).put("createdAt", it.createdAt)
            }))
            val sum = summaries.summary(sid)
            s.put("summary", JSONObject().put("state", sum.state.name.lowercase()).put("stale", sum.state.name == "Stale").put("format", format)
                .put("text", sum.text ?: JSONObject.NULL).put("sections", JSONArray(sum.sections.map { JSONObject().put("label", it.label).put("text", it.text) }))
                .put("generatedAt", sum.generatedAt ?: JSONObject.NULL))
            sessions.put(s)
        }
        val facts = JSONArray()
        val allFacts = doc.getJSONArray("facts")
        for (i in 0 until allFacts.length()) {
            val f = allFacts.getJSONObject(i)
            if ((client == null || f.getString("client") == client) && (session == null || f.getString("session") == session)) facts.put(f)
        }
        AppLog.i(TAG, "results", LogCtx.NONE, "sessions" to sessions.length(), "facts" to facts.length())
        return JSONObject().put("embedModel", doc.get("embedModel")).put("summary_format", format).put("sessions", sessions).put("facts", facts).put("answers", doc.getJSONArray("answers"))
    }

    override fun swapRoles(session: String): JSONObject {
        val store = MindStore.get(app)
        if (!store.sessionExists(session)) throw QaException(QaCode.NOT_FOUND, "no such session")
        // The same two calls as MainViewModel.swapRoles: the swap, then the summary is flagged stale and rebuilt.
        ClientRepository(app).swapRoles(session) ?: throw QaException(QaCode.BAD_REQUEST, "nothing to swap: no speaker map, or not exactly one therapist and one patient")
        SessionSummaryRepository.create(app).onRolesSwapped(session)
        val m = store.currentSpeakerMap(session)
        AppLog.i(TAG, "swap_roles", LogCtx.NONE, "version" to m?.version)
        return JSONObject().put("session", session).put("version", m?.version ?: JSONObject.NULL).put("map", runCatching { JSONObject(m?.mapJson ?: "{}") }.getOrDefault(JSONObject()))
    }

    override fun setSummaryFormat(id: String): JSONObject {
        if (id !in SummaryConfig.FORMATS) throw QaException(QaCode.BAD_REQUEST, "unknown format; known: ${SummaryConfig.FORMATS.keys.joinToString(",")}")
        val changed = SessionSummaryRepository.create(app).changeFormat(id)
        AppLog.i(TAG, "set_summary_format", LogCtx.NONE, "format" to id, "changed" to changed)
        return JSONObject().put("format", id).put("changed", changed)
    }

    override fun setGemmaVariant(key: String): JSONObject {
        val variant = com.therapylocal.app.models.GemmaVariant.entries.firstOrNull { it.key == key }
            ?: throw QaException(QaCode.BAD_REQUEST, "unknown variant; known: ${com.therapylocal.app.models.GemmaVariant.entries.joinToString(",") { it.key }}")
        val before = ModelSetup.gemmaVariant(app)
        ModelSetup.selectGemma(app, variant)
        AppLog.i(TAG, "set_gemma_variant", LogCtx.NONE, "variant" to variant.key, "changed" to (before != variant))
        return JSONObject().put("variant", variant.key).put("changed", before != variant).put("ready", ModelSetup.isReady(app))
    }

    override fun reset(): JSONObject {
        // Belt and braces next to the dispatcher's check: a data-deleting command never runs on an id that does not end in .qa.
        check(QaGuard.isQaApplicationId(applicationId)) { "not the QA build" }
        Vault.erase(app) // keys, database, retained audio, dev in/out; the models (external files/models) are not touched
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove(SummaryFormatSetting.PREF).apply()
        File(filesRoot, "api").listFiles { f -> f.extension == "json" }?.forEach { it.delete() } // earlier results hold test transcripts
        AppLog.i(TAG, "reset", LogCtx.NONE, "models_ready" to ModelSetup.isReady(app))
        return JSONObject().put("erased", true).put("models_kept", true).put("models_ready", ModelSetup.isReady(app))
    }

    companion object {
        const val TAG = "QaApi"
        private const val SETTLE_MS = 600L
    }
}
