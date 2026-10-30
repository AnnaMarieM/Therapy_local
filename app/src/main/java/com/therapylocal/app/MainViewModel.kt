package com.therapylocal.app

import android.app.Application
import android.content.Context
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.data.RecordingRow
import com.therapylocal.app.data.SegmentBuilder
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobRunner
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.LiveCues
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.models.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { Preparing, Ready, Failed }

/** Client search on the profile: [hits] is null before the first search. */
data class SearchState(val listening: Boolean = false, val searching: Boolean = false, val hits: List<SearchHit>? = null, val info: String? = null)

data class UiState(
    val phase: Phase = Phase.Preparing,
    val prepareMsg: String = "Starting",
    val engine: String = "whisper", // "gemma" or "whisper": which engine transcribes recordings (in the background, after they stop)
    val whisperSize: String = "base",
    val language: String = "auto", // Whisper language: "auto" or a code such as "en"
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
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            engine = prefs.getString("engine", "whisper")!!,
            whisperSize = prefs.getString("whisper_size", "base")!!,
            language = prefs.getString("language", "auto")!!,
            backendOverride = prefs.getString("backend_override", "auto")!!,
            testFiles = if (BuildConfig.DEBUG) TestAudio.list(app) else emptyList(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val store get() = MindStore.get(ctx)
    private val embedder = Embedder(app)

    /** Realtime fact cues while recording: call `liveCues.start(client)` before startRecording(); it stops itself when the recording ends. */
    val liveCues = LiveCues(embedder, MindStore.get(app)) { prefs.liveCueSettings() }
    private var prepareJob: Job? = null

    private var recorder: ChunkRecorder? = null
    private var pipelineJob: Job? = null

    private val clients = ClientRepository(app)
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null
    private var voiceRecorder: ChunkRecorder? = null

    init {
        BackendPolicy.override = _state.value.backendOverride
        refreshRecordings()
        // Memory-lab launches only need the pipeline's models; loading the embedder here too would take memory they need.
        if (!skipPrepare) prepare()
    }

    // ---- settings -------------------------------------------------------------------------------------------

    fun setEngine(engine: String) = change(reload = false) { prefs.edit().putString("engine", engine).apply(); it.copy(engine = engine) }
    fun setWhisperSize(size: String) = change(reload = false) { prefs.edit().putString("whisper_size", size).apply(); it.copy(whisperSize = size) }
    fun setLanguage(lang: String) = change(reload = false) { prefs.edit().putString("language", lang).apply(); it.copy(language = lang) }

    /** Debug: force a backend to compare NPU, GPU and CPU honestly. Reloads both engines. */
    fun setBackendOverride(o: String) = change(reload = true) {
        prefs.edit().putString("backend_override", o).apply()
        BackendPolicy.override = o
        it.copy(backendOverride = o)
    }

    /** The speech settings are read by the transcribe job; [prepare] again because they change which AI files are needed. */
    private fun change(reload: Boolean, f: (UiState) -> UiState) {
        if (_state.value.recording) return
        _state.update(f)
        val s = _state.value
        AppLog.i(TAG, "settings_changed", LogCtx.NONE, "engine" to s.engine, "whisper_size" to s.whisperSize, "language" to s.language, "backend_override" to s.backendOverride)
        if (reload) embedder.close()
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

    /** Records [session] of [client]: its row is created now (the first time), and its audio is processed once every chunk is saved. */
    fun startRecording(client: String, session: String) = beginRecording(client to session) { rec -> rec.start() }

    /** Debug: a recording outside any session. Its audio is kept but never processed. */
    fun startRecording() = beginRecording(null) { rec -> rec.start() }

    /** Debug: replay `files/test/<name>` through the real chunker instead of the microphone. */
    fun debugRecordFile(name: String) {
        val pcm = TestAudio.load(ctx, name)
        AppLog.i(TAG, "debug_record_file", LogCtx.NONE, "file" to name, "samples" to pcm.size, "audio_sec" to "%.1f".format(pcm.size / 16000.0))
        beginRecording(null) { rec -> rec.startFromPcm(pcm) }
    }

    /** [owner] is (client, session), null for a debug recording. */
    private fun beginRecording(owner: Pair<String, String>?, startSource: (ChunkRecorder) -> Unit) {
        val s = _state.value
        if (s.recording) return
        viewModelScope.launch {
            // The audio stays on the phone until it is processed; a recording that cannot be saved is better not started.
            if (withContext(Dispatchers.IO) { freeBytes() } < ChunkConfig.MIN_FREE_BYTES) {
                _state.update { it.copy(error = "Not enough free storage to record. Free some space and try again.") }
                return@launch
            }
            val rid = withContext(Dispatchers.IO) {
                owner?.let { (client, session) -> store.createSession(session, client, System.currentTimeMillis()) }
                store.createRecording(owner?.second, s.engine, "")
            }
            val c = LogCtx(rid)
            val rec = ChunkRecorder.recording(c)
            rec.tap = liveCues::feed
            try {
                startSource(rec)
            } catch (t: Throwable) {
                AppLog.e(TAG, "record_start_failed", c, t)
                _state.update { it.copy(error = "Recording failed: ${t.message}") }
                return@launch
            }
            recorder = rec
            JobRunner.recordingActive = true
            _state.update { it.copy(recording = true, activeRecordingId = rid, status = "Recording", error = null) }
            pipelineJob = launch(Dispatchers.Default) {
                try {
                    saveChunks(rid, rec)
                    liveCues.stop()
                    // Only now, with the last chunk saved, is the recording handed to the queue (a deleted session has nothing to process).
                    owner?.let { (_, session) -> withContext(Dispatchers.IO) { if (store.sessionExists(session)) JobGraph.enqueueRecording(Jobs.queue(ctx), session, rid) } }
                } finally {
                    // The queue does not start new jobs while recording; anything that waited runs now.
                    JobRunner.recordingActive = false
                    MemoryService.resumePending(ctx)
                }
                recorder = null
                _state.update { it.copy(recording = false, activeRecordingId = null, status = "Done") }
                refreshRecordings()
            }
        }
    }

    /** Internal storage the app can still use, counting cache the system would clear for it. */
    private fun freeBytes(): Long {
        val storage = ctx.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(ctx.filesDir))
    }

    /** Every chunk is kept encrypted for the transcribe job, with a row that has no text yet. Nothing is transcribed while recording. */
    private suspend fun saveChunks(rid: Long, rec: ChunkRecorder) {
        val c = LogCtx(rid)
        var saved = 0
        for (chunk in rec.chunks) {
            try {
                withContext(Dispatchers.IO) {
                    store.putChunk(ChunkRow(rid, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, null))
                    PendingAudio.save(ctx, rid, chunk)
                }
                saved++
            } catch (t: Throwable) {
                AppLog.e(TAG, "chunk_save_failed", c.chunk(chunk.idx), t, "code" to "IO")
                _state.update { it.copy(error = "Part of the recording could not be saved (IO).") }
            }
        }
        AppLog.i(TAG, "recording_saved", c, "chunks" to saved)
    }

    fun stopRecording() {
        val rec = recorder ?: return
        viewModelScope.launch(Dispatchers.Default) {
            rec.stop()
            _state.update { it.copy(recording = false, status = "Finishing last chunks") }
        }
    }

    fun pauseRecording() {
        recorder?.isPaused = true
        _state.update { it.copy(status = "Paused") }
    }

    fun resumeRecording() {
        recorder?.isPaused = false
        _state.update { it.copy(status = "Listening") }
    }

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
                val vec = if (_state.value.phase == Phase.Ready) embedder.embedText(query, query = true, LogCtx.NONE) else null
                SearchState(hits = clients.search(client, vec, query), info = if (vec == null) "Transcripts can be searched once setup finishes." else null)
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
        val rec = ChunkRecorder.query(LogCtx.NONE)
        try {
            rec.start()
        } catch (t: Throwable) {
            AppLog.e(TAG, "voice_search_start_failed", LogCtx.NONE, t)
            _search.value = SearchState(info = "The microphone could not be opened.")
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
                _search.value = SearchState(info = "Too short: hold the button while you speak.")
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
            AppLog.e(TAG, "search_failed", LogCtx.NONE, t)
            SearchState(info = "Search failed. Please try again.")
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
            AppLog.i(TAG, "delete_all_verified", LogCtx.NONE, "removed_recordings" to removed, "remaining_recordings" to store.recordings().size, "audio_files_on_disk" to (File(MemoryFiles.secureRoot(ctx), "audio").walkTopDown().count { it.isFile }))
            _state.update { it.copy(selected = null) }
            refreshRecordings()
        }
    }

    override fun onCleared() {
        recorder?.stop()
        voiceRecorder?.stop()
        embedder.close()
    }

    companion object {
        const val TAG = "App"

        /** Set by a debug memory-lab launch before the view model is created. */
        @Volatile
        var skipPrepare = false
    }
}
