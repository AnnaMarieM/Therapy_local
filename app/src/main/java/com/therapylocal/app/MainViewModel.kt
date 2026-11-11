package com.therapylocal.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therapylocal.app.R
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.data.RecordingRow
import com.therapylocal.app.data.SegmentBuilder
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.LiveCues
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.session.SessionController
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.summary.SessionSummary
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.data.SessionSummaryRepository
import com.therapylocal.app.ui.models.SpeakerRoles
import com.therapylocal.app.ui.models.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { Preparing, Ready, Failed }

/**
 * Client search on the profile: [hits] is null before the first search. [paused]: the search model is unloaded while sessions are processed
 * (or its engine is away), which is told quietly, never as an error; word hits in facts still show.
 */
data class SearchState(
    val listening: Boolean = false, val searching: Boolean = false, val hits: List<SearchHit>? = null, val info: String? = null,
    val paused: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.Preparing,
    val prepareMsg: String = "Starting",
    val backendOverride: String = "auto", // debug: auto, npu, gpu, cpu
    val embedBackend: String = "-",
    val recording: Boolean = false,
    val activeRecordingId: Long? = null, // set until every chunk of the recording is saved
    val status: String = "",
    val recordings: List<RecordingRow> = emptyList(),
    val selected: Long? = null,
    val selectedChunks: List<ChunkRow> = emptyList(),
    val selectedMerged: String = "",
    val testFiles: List<String> = emptyList(),
    val error: String? = null, // setup problems only
    val recordingError: String? = null, // the session's recording problem, from SessionController
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            backendOverride = prefs.getString("backend_override", "auto")!!,
            testFiles = if (BuildConfig.DEBUG) TestAudio.list(app) else emptyList(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val store get() = MindStore.get(ctx)
    private val embedder = Engines.embedder(app)

    /** Realtime fact cues of the running session (owned by [SessionController]). */
    val liveCues: LiveCues get() = SessionController.liveCues
    private var prepareJob: Job? = null

    private val clients = ClientRepository(app)
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null
    private var voiceRecorder: ChunkRecorder? = null

    init {
        SessionController.init(app)
        BackendPolicy.override = _state.value.backendOverride
        viewModelScope.launch {
            SessionController.state.collect { r ->
                _state.update { it.copy(recording = r.active, activeRecordingId = r.recordingId, recordingError = r.error) }
                // A session started before setup finished gets its cues as soon as the embedder is ready.
                if (r.active && !r.cuesOn && _state.value.phase == Phase.Ready) SessionController.enableCues()
            }
        }
        refreshRecordings()
        // Memory-lab launches only need the pipeline's models; loading the embedder here too would take memory they need.
        if (!skipPrepare) prepare()
    }

    // ---- settings -------------------------------------------------------------------------------------------

    /** Debug: force a backend to compare NPU, GPU and CPU honestly. Reloads both engines. */
    fun setBackendOverride(o: String) {
        if (_state.value.recording) return
        prefs.edit().putString("backend_override", o).apply()
        BackendPolicy.override = o
        _state.update { it.copy(backendOverride = o) }
        AppLog.i(TAG, "settings_changed", LogCtx.NONE, "backend_override" to o)
        embedder.close()
        prepare()
    }

    // ---- model preparation ----------------------------------------------------------------------------------

    private fun prepare() {
        prepareJob?.cancel()
        _state.update { it.copy(phase = Phase.Preparing, error = null, prepareMsg = "Getting ready") }
        prepareJob = viewModelScope.launch {
            ModelSetup.start(ctx) // the required file set may have changed with the settings
            val p = ModelSetup.progress.first { it.state == SetupState.Ready || it.state == SetupState.Failed }
            if (p.state == SetupState.Failed) {
                _state.update { it.copy(phase = Phase.Failed, error = p.message) }
                return@launch
            }
            loadEngines()
        }
    }

    /** Only the embedder, for live cues: speech is transcribed after the session by the queue, never while recording. */
    private suspend fun loadEngines() {
        try {
            _state.update { it.copy(prepareMsg = "Getting ready") }
            embedder.load()
            _state.update { it.copy(phase = Phase.Ready, embedBackend = "text ${embedder.backend} / audio CPU", prepareMsg = "Ready") }
            SessionController.enableCues()
        } catch (t: Throwable) {
            AppLog.e(TAG, "prepare_failed", LogCtx.NONE, t)
            _state.update { it.copy(phase = Phase.Failed, error = t.message) }
        }
    }

    fun retry() {
        ModelSetup.retry(ctx)
        prepare()
    }

    // ---- recording ------------------------------------------------------------------------------------------

    /** Records [session] of [client] through the process-wide [SessionController]; live cues run when the embedder is ready. */
    fun startRecording(client: String, session: String) {
        SessionController.start(client, cues = _state.value.phase == Phase.Ready, session = session)
    }

    /** Debug: a recording outside any session. Its audio is kept but never processed. */
    fun startRecording() {
        SessionController.startDebug()
    }

    /** Debug: replay `files/test/<name>` through the real chunker instead of the microphone. */
    fun debugRecordFile(name: String) {
        val pcm = TestAudio.load(ctx, name)
        AppLog.i(TAG, "debug_record_file", LogCtx.NONE, "file" to name, "samples" to pcm.size, "audio_sec" to "%.1f".format(pcm.size / 16000.0))
        SessionController.startDebug(pcm)
    }

    fun stopRecording() = SessionController.finish()

    fun pauseRecording() = SessionController.pause()

    fun resumeRecording() = SessionController.resume()

    // ---- speaker roles --------------------------------------------------------------------------------------

    private val _roles = MutableStateFlow<Map<String, SpeakerRoles>>(emptyMap())

    /** The current [SpeakerRoles] of every session loaded with [loadRoles] or changed with [swapRoles]; a session without a role guess is absent. */
    val roles: StateFlow<Map<String, SpeakerRoles>> = _roles.asStateFlow()

    fun loadRoles(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) { setRoles(sessionId, clients.speakerRoles(sessionId)) }
    }

    /** "Is this incorrect?": swaps therapist and patient of [sessionId] off the main thread, then [roles] holds the new version. */
    fun swapRoles(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clients.swapRoles(sessionId)?.let {
                    setRoles(sessionId, it)
                    // The summary was made with the old roles: marked stale and, if there was one, made again.
                    summaryRepo.onRolesSwapped(sessionId)
                    refreshSummaries()
                }
            } catch (t: Throwable) {
                AppLog.eRedacted(TAG, "swap_roles_failed", LogCtx.NONE, t)
            }
        }
    }

    private val summaryRepo = SessionSummaryRepository.create(app)
    private val _lastSummaries = MutableStateFlow<Map<String, SessionSummary>>(emptyMap())

    /** The last-session summary of every client loaded with [loadLastSummary], by client id; a client without a transcript is absent. State: none, generating, ready, stale. */
    val lastSummaries: StateFlow<Map<String, SessionSummary>> = _lastSummaries.asStateFlow()

    private val summaryClients = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun loadLastSummary(clientId: String) {
        summaryClients += clientId
        viewModelScope.launch(Dispatchers.IO) { refreshSummary(clientId) }
    }

    /** Makes the summary of [sessionId] again (replaced when done; its state is generating meanwhile). */
    fun regenerateSummary(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                summaryRepo.regenerate(sessionId)
                refreshSummaries()
            } catch (t: Throwable) {
                AppLog.eRedacted(TAG, "regenerate_summary_failed", LogCtx.NONE, t)
            }
        }
    }

    private fun refreshSummaries() = summaryClients.toList().forEach { refreshSummary(it) }

    private fun refreshSummary(clientId: String) {
        try {
            val s = summaryRepo.lastSessionSummary(clientId)
            _lastSummaries.update { if (s == null) it - clientId else it + (clientId to s) }
        } catch (t: Throwable) {
            AppLog.eRedacted(TAG, "load_summary_failed", LogCtx.NONE, t)
        }
    }

    init {
        // A summary job starting or finishing changes the state of a summary; progress ticks of other jobs do not.
        viewModelScope.launch(Dispatchers.IO) {
            Jobs.snapshot.map { snap -> snap.jobs.any { it.kind == JobGraph.SUMMARY && (it.status == JobStatus.Pending || it.status == JobStatus.Running) } }.distinctUntilChanged().collect { refreshSummaries() }
        }
    }

    /** The session's transcript as text with role labels (blocking: call off the main thread). */
    fun formattedTranscript(sessionId: String): String = clients.formattedTranscript(sessionId)

    private fun setRoles(sessionId: String, roles: SpeakerRoles?) = _roles.update { if (roles == null) it - sessionId else it + (sessionId to roles) }

    // ---- client search --------------------------------------------------------------------------------------

    /** Searches [client]'s transcripts by meaning (the query embedded as a search query) and its facts by words. Facts only until the embedder is ready. */
    fun searchText(client: String, query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchState()
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            showSearch {
                val ready = _state.value.phase == Phase.Ready
                // Held back while sessions are processed: no call into the (unloaded) embedder, a quiet note instead of an error.
                val paused = ready && !embedder.usable
                val vec = if (ready && !paused) embedder.embedText(query, query = true, LogCtx.NONE) else null
                SearchState(
                    hits = clients.search(client, vec, query), paused = paused,
                    info = if (vec == null && !paused) ctx.getString(R.string.vm_search_after_setup) else null,
                )
            }
        }
    }

    /**
     * Hold-to-search: records until [stopVoiceSearch] or [ChunkConfig.QUERY_MAX_SECONDS], then searches with the audio embedding itself
     * (no transcription). False if it cannot start now: the embedder is not ready, or a session or another clip is recording.
     */
    fun startVoiceSearch(client: String): Boolean {
        val s = _state.value
        if (s.phase != Phase.Ready || s.recording || voiceRecorder != null) return false
        if (!embedder.usable) {
            _search.value = SearchState(paused = true)
            return false
        }
        val rec = ChunkRecorder.query(LogCtx.NONE)
        try {
            rec.start()
        } catch (t: Throwable) {
            AppLog.e(TAG, "voice_search_start_failed", LogCtx.NONE, t)
            _search.value = SearchState(info = ctx.getString(R.string.vm_mic_open_failed_short))
            return false
        }
        voiceRecorder = rec
        searchJob?.cancel()
        _search.value = SearchState(listening = true)
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            // The one clip arrives at release, or at the length cap while still recording.
            val clip = rec.chunks.receiveCatching().getOrNull()
            stopVoiceSearch()
            if (clip == null) {
                _search.value = SearchState(info = ctx.getString(R.string.vm_search_too_short))
                return@launch
            }
            showSearch {
                val vec = embedder.embedAudio(clip.pcm, LogCtx.NONE, role = "query")
                SearchState(hits = withContext(Dispatchers.IO) { clients.search(client, vec, null) })
            }
        }
        return true
    }

    private suspend fun showSearch(run: suspend () -> SearchState) {
        _search.value = SearchState(searching = true)
        _search.value = try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Suspended between the check and the call (processing just started): the same quiet pause.
            if (!embedder.usable) {
                AppLog.i(TAG, "search_paused", LogCtx.NONE, "error" to t.javaClass.simpleName)
                SearchState(paused = true)
            } else {
                AppLog.e(TAG, "search_failed", LogCtx.NONE, t)
                SearchState(info = ctx.getString(R.string.vm_search_failed))
            }
        }
    }

    fun stopVoiceSearch() {
        val rec = synchronized(this) { voiceRecorder.also { voiceRecorder = null } } ?: return
        viewModelScope.launch(Dispatchers.Default) { rec.stop() }
    }

    fun clearSearch() {
        searchJob?.cancel()
        stopVoiceSearch()
        _search.value = SearchState()
    }

    // ---- recordings list, delete ----------------------------------------------------------------------------

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.recordings()
            val sel = _state.value.selected
            val chunks = if (sel != null) store.chunks(sel) else emptyList()
            val merged = if (chunks.isNotEmpty()) mergedText(sel!!, chunks) else ""
            _state.update { it.copy(recordings = list, selectedChunks = chunks, selectedMerged = merged) }
        }
    }

    /** The whole recording as one text, with the words repeated in each 5 s overlap removed. */
    private fun mergedText(rid: Long, chunks: List<ChunkRow>): String {
        val text = SegmentBuilder.build(chunks).joinToString(" ") { it.text }
        AppLog.i(TAG, "transcript_merge", LogCtx(rid = rid), "chunks" to chunks.size, "merged_chars" to text.length)
        return text
    }

    fun select(id: Long?) {
        _state.update { it.copy(selected = id, selectedChunks = emptyList(), selectedMerged = "") }
        refreshRecordings()
    }

    fun deleteRecording(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = store.deleteRecording(id)
            PendingAudio.delete(ctx, id)
            AppLog.i(TAG, "delete_verified", LogCtx(rid = id), "removed" to removed, "chunks_left" to store.chunks(id).size, "audio_files_on_disk" to pendingAudioFiles(id))
            if (_state.value.selected == id) _state.update { it.copy(selected = null) }
            refreshRecordings()
        }
    }

    private fun pendingAudioFiles(rid: Long): Int = PendingAudio.dir(ctx, rid).walkTopDown().count { it.isFile }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = store.recordings().count { store.deleteRecording(it.id) }
            File(MemoryFiles.secureRoot(ctx), "audio").deleteRecursively()
            PendingAudio.deleteAllRetained(ctx)
            AppLog.i(TAG, "delete_all_verified", LogCtx.NONE, "removed_recordings" to removed, "remaining_recordings" to store.recordings().size, "audio_files_on_disk" to (File(MemoryFiles.secureRoot(ctx), "audio").walkTopDown().count { it.isFile }))
            _state.update { it.copy(selected = null) }
            refreshRecordings()
        }
    }

    // The embedder and a running session belong to the process (Engines, SessionController), not to this screen.
    override fun onCleared() {
        voiceRecorder?.stop()
    }

    companion object {
        const val TAG = "App"

        /** Set by a debug memory-lab launch before the view model is created. */
        @Volatile
        var skipPrepare = false
    }
}
