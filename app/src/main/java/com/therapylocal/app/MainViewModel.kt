package com.therapylocal.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therapylocal.app.asr.AsrEngine
import com.therapylocal.app.asr.GemmaAsr
import com.therapylocal.app.asr.SherpaWhisperAsr
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.SearchConfig
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.HitGroup
import com.therapylocal.app.data.RecordingRow
import com.therapylocal.app.data.Search
import com.therapylocal.app.data.Store
import com.therapylocal.app.data.TranscriptClean
import com.therapylocal.app.data.TranscriptMerge
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelManager
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSpec
import com.therapylocal.app.models.ModelState
import com.therapylocal.app.models.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class Phase { Preparing, Ready, Failed }

data class UiState(
    val phase: Phase = Phase.Preparing,
    val prepareMsg: String = "Starting",
    val models: List<ModelStatus> = emptyList(),
    val engine: String = "whisper", // "gemma" or "whisper": which engine writes the transcript shown for each chunk
    val whisperSize: String = "base",
    val language: String = "auto", // Whisper language: "auto" or a code such as "en"
    val backendOverride: String = "auto", // debug: auto, npu, gpu, cpu
    val asrBackend: String = "-",
    val embedBackend: String = "-",
    val recording: Boolean = false,
    val activeRecordingId: Long? = null,
    val pendingChunks: Int = 0,
    val status: String = "",
    val recordings: List<RecordingRow> = emptyList(),
    val selected: Long? = null,
    val selectedChunks: List<ChunkRow> = emptyList(),
    val selectedMerged: String = "",
    val query: String = "",
    val voiceRecording: Boolean = false,
    val searching: Boolean = false,
    val hits: List<HitGroup> = emptyList(),
    val searchInfo: String = "",
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

    private val store = Store(app)
    private val models = ModelManager(app, viewModelScope)
    private val embedder = Embedder(app)
    private var asr: AsrEngine? = null
    private var prepareJob: Job? = null

    private var recorder: ChunkRecorder? = null
    private var voiceRecorder: ChunkRecorder? = null
    private var pipelineJob: Job? = null
    private val queryCounter = AtomicInteger(0)

    init {
        BackendPolicy.override = _state.value.backendOverride
        viewModelScope.launch {
            models.status.collect { m -> _state.update { it.copy(models = needed().mapNotNull { s -> m[s.id] }) } }
        }
        refreshRecordings()
        prepare()
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

    private fun change(reload: Boolean, f: (UiState) -> UiState) {
        if (_state.value.recording || _state.value.voiceRecording) return
        _state.update(f)
        val s = _state.value
        AppLog.i(TAG, "settings_changed", LogCtx.NONE, "engine" to s.engine, "whisper_size" to s.whisperSize, "language" to s.language, "backend_override" to s.backendOverride)
        if (reload) embedder.close()
        asr?.close()
        asr = null
        prepare()
    }

    // ---- model preparation ----------------------------------------------------------------------------------

    private fun needed(): List<ModelSpec> {
        val s = _state.value
        return listOf(ModelRegistry.EMBED, if (s.engine == "gemma") ModelRegistry.GEMMA else ModelRegistry.whisperFor(s.whisperSize))
    }

    private fun prepare() {
        prepareJob?.cancel()
        _state.update { it.copy(phase = Phase.Preparing, error = null, prepareMsg = "Checking models") }
        prepareJob = viewModelScope.launch {
            val specs = needed()
            specs.forEach { models.ensure(it) }
            val settled = models.status
                .map { m -> specs.map { m[it.id] } }
                .first { st -> st.any { it?.state == ModelState.Failed } || st.all { it?.state == ModelState.Ready } }
            val failed = settled.firstOrNull { it?.state == ModelState.Failed }
            if (failed != null) {
                _state.update { it.copy(phase = Phase.Failed, error = failed.error) }
                return@launch
            }
            loadEngines()
        }
    }

    private suspend fun loadEngines() {
        val s = _state.value
        try {
            _state.update { it.copy(prepareMsg = "Loading EmbeddingGemma 2 (text + audio)") }
            embedder.load()
            _state.update { it.copy(embedBackend = "text ${embedder.backend} / audio CPU", prepareMsg = "Loading ${s.engine}") }
            val engine: AsrEngine = if (s.engine == "gemma") GemmaAsr(ctx) else SherpaWhisperAsr(ctx, s.whisperSize, s.language)
            engine.load()
            asr = engine
            _state.update { it.copy(phase = Phase.Ready, asrBackend = "${engine.modelName} ${engine.backend}", prepareMsg = "Ready") }
        } catch (t: Throwable) {
            AppLog.e(TAG, "prepare_failed", LogCtx.NONE, t)
            _state.update { it.copy(phase = Phase.Failed, error = t.message) }
        }
    }

    fun retry() {
        needed().forEach { models.retry(it) }
        prepare()
    }

    // ---- recording ------------------------------------------------------------------------------------------

    fun startRecording() = beginRecording { rec -> rec.start() }

    /** Debug: replay `files/test/<name>` through the real chunker instead of the microphone. */
    fun debugRecordFile(name: String) {
        val pcm = TestAudio.load(ctx, name)
        AppLog.i(TAG, "debug_record_file", LogCtx.NONE, "file" to name, "samples" to pcm.size, "audio_sec" to "%.1f".format(pcm.size / 16000.0))
        beginRecording { rec -> rec.startFromPcm(pcm) }
    }

    private fun beginRecording(startSource: (ChunkRecorder) -> Unit) {
        val s = _state.value
        val engine = asr ?: return
        if (s.phase != Phase.Ready || s.recording) return
        viewModelScope.launch {
            val rid = withContext(Dispatchers.IO) { store.createRecording(engine.engineName, engine.modelName) }
            val c = LogCtx(rid)
            val rec = ChunkRecorder.recording(c)
            try {
                startSource(rec)
            } catch (t: Throwable) {
                AppLog.e(TAG, "record_start_failed", c, t)
                _state.update { it.copy(error = "Recording failed: ${t.message}") }
                return@launch
            }
            recorder = rec
            _state.update { it.copy(recording = true, activeRecordingId = rid, status = "Recording", error = null, pendingChunks = 0) }
            pipelineJob = launch(Dispatchers.Default) {
                runPipeline(rid, rec, engine)
                recorder = null
                _state.update { it.copy(recording = false, activeRecordingId = null, status = "Done") }
                refreshRecordings()
            }
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        viewModelScope.launch(Dispatchers.Default) {
            rec.stop()
            _state.update { it.copy(recording = false, status = "Finishing last chunks") }
        }
    }

    /** Per chunk: embed the AUDIO directly, transcribe it for reading, embed the transcript too (for the quality comparison), store. */
    private suspend fun runPipeline(rid: Long, rec: ChunkRecorder, engine: AsrEngine) {
        val ctx0 = LogCtx(rid)
        var processed = 0
        for (chunk in rec.chunks) {
            val c = ctx0.chunk(chunk.idx)
            _state.update { it.copy(pendingChunks = it.pendingChunks + 1, status = "Chunk ${chunk.idx}: embedding audio") }
            try {
                AppLog.span(TAG, "chunk_pipeline", c, "engine" to engine.engineName, "model" to engine.modelName, "start_ms" to chunk.startMs, "end_ms" to chunk.endMs, "overlap_ms" to chunk.overlapMs) { span ->
                    val audioVec = embedder.embedAudio(chunk.pcm, c)
                    _state.update { it.copy(status = "Chunk ${chunk.idx}: transcribing (${engine.modelName})") }
                    val result = engine.transcribe(chunk.pcm, c)
                    span.put("text_chars", result.text.length)
                    val textVec = if (result.text.isBlank()) {
                        AppLog.w(TAG, "chunk_empty_transcript", c, "audio_sec" to "%.2f".format(result.audioSec))
                        null
                    } else {
                        embedder.embedText(result.text, false, c)
                    }
                    val row = ChunkRow(
                        0, rid, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, result.text,
                        engine.engineName, engine.modelName, result.wallMs, EmbeddingConfig.MODEL_ID, audioVec.size, "text ${embedder.backend} / audio CPU",
                    )
                    withContext(Dispatchers.IO) { store.insertChunk(row, audioVec, textVec) }
                    processed++
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "chunk_failed", c, t)
                _state.update { it.copy(error = "Chunk ${chunk.idx} failed: ${t.message}") }
            } finally {
                _state.update { it.copy(pendingChunks = (it.pendingChunks - 1).coerceAtLeast(0)) }
                refreshRecordings()
            }
        }
        AppLog.i(TAG, "recording_done", ctx0, "chunks_stored" to processed)
    }

    // ---- recordings list, delete ----------------------------------------------------------------------------

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.listRecordings()
            val sel = _state.value.selected
            val chunks = if (sel != null) store.chunks(sel) else emptyList()
            val merged = if (chunks.isNotEmpty()) mergedText(sel!!, chunks) else ""
            _state.update { it.copy(recordings = list, selectedChunks = chunks, selectedMerged = merged) }
        }
    }

    /** The whole recording as one text, with the words repeated in each 5 s overlap removed. */
    private fun mergedText(rid: Long, chunks: List<ChunkRow>): String {
        val r = TranscriptMerge.merge(chunks.map { TranscriptClean.stripPromptEcho(it.text) })
        AppLog.i(TAG, "transcript_merge", LogCtx(rid = rid), "chunks" to chunks.size, "removed_words" to r.removedWords.joinToString(","), "merged_chars" to r.text.length)
        return r.text
    }

    fun select(id: Long?) {
        _state.update { it.copy(selected = id, selectedChunks = emptyList(), selectedMerged = "") }
        refreshRecordings()
    }

    fun deleteRecording(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = store.deleteRecording(id)
            val (recs, chunks) = store.counts()
            AppLog.i(TAG, "delete_verified", LogCtx(rid = id), "removed_recordings" to r.recordings, "removed_chunks" to r.chunks, "remaining_recordings" to recs, "remaining_chunks" to chunks, "audio_files_on_disk" to 0)
            if (_state.value.selected == id) _state.update { it.copy(selected = null) }
            _state.update { it.copy(hits = it.hits.filter { h -> h.recordingId != id }) }
            refreshRecordings()
        }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val r = store.deleteAll()
            val (recs, chunks) = store.counts()
            AppLog.i(TAG, "delete_all_verified", LogCtx.NONE, "removed_recordings" to r.recordings, "removed_chunks" to r.chunks, "remaining_recordings" to recs, "remaining_chunks" to chunks, "audio_files_on_disk" to 0)
            _state.update { it.copy(selected = null, hits = emptyList()) }
            refreshRecordings()
        }
    }

    // ---- search ---------------------------------------------------------------------------------------------

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Text query: embedded as text into the same space as the stored chunk AUDIO vectors. */
    fun searchText() {
        val q = _state.value.query.trim()
        if (q.isEmpty() || _state.value.phase != Phase.Ready) return
        val c = LogCtx(cid = "q${queryCounter.incrementAndGet()}")
        viewModelScope.launch(Dispatchers.Default) {
            runSearch("text", c) { embedder.embedText(q, true, c) }
        }
    }

    /** Voice query: the clip's AUDIO is embedded directly. It is never transcribed. */
    fun startVoiceSearch() = beginVoiceSearch { rec -> rec.start() }

    /** Debug: use `files/test/<name>` as the spoken query. */
    fun debugQueryFile(name: String) {
        val pcm = TestAudio.load(ctx, name)
        AppLog.i(TAG, "debug_query_file", LogCtx.NONE, "file" to name, "audio_sec" to "%.1f".format(pcm.size / 16000.0))
        beginVoiceSearch { rec -> rec.startFromPcm(pcm) }
    }

    private fun beginVoiceSearch(startSource: (ChunkRecorder) -> Unit) {
        if (_state.value.phase != Phase.Ready || _state.value.voiceRecording || _state.value.recording) return
        val c = LogCtx(cid = "q${queryCounter.incrementAndGet()}")
        val rec = ChunkRecorder.query(c)
        try {
            startSource(rec)
        } catch (t: Throwable) {
            _state.update { it.copy(error = "Recording failed: ${t.message}") }
            return
        }
        voiceRecorder = rec
        _state.update { it.copy(voiceRecording = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            var clip: com.therapylocal.app.audio.AudioChunk? = null
            for (chunk in rec.chunks) if (clip == null) clip = chunk
            voiceRecorder = null
            _state.update { it.copy(voiceRecording = false) }
            val heard = clip
            if (heard == null) {
                _state.update { it.copy(searching = false, searchInfo = "Too short, hold the button while speaking") }
                return@launch
            }
            runSearch("voice", c) { embedder.embedAudio(heard.pcm, c, role = "query") }
        }
    }

    fun stopVoiceSearch() {
        val rec = voiceRecorder ?: return
        viewModelScope.launch(Dispatchers.Default) { rec.stop() }
    }

    private suspend fun runSearch(kind: String, c: LogCtx, embedQuery: suspend () -> FloatArray) {
        _state.update { it.copy(searching = true, searchInfo = "Searching") }
        try {
            AppLog.span(TAG, "search", c, "kind" to kind) { span ->
                val t0 = System.nanoTime()
                val qv = embedQuery()
                val embedMs = (System.nanoTime() - t0) / 1_000_000
                val raw = withContext(Dispatchers.IO) { Search.topK(store, qv, SearchConfig.RAW_TOP_K, kind, c) }
                val groups = Search.collapse(raw, c).take(SearchConfig.SHOWN)
                span.put("embed_ms", embedMs)
                span.put("groups", groups.size)
                _state.update { it.copy(hits = groups, searching = false, searchInfo = "${groups.size} results, query embedded in $embedMs ms") }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "search_failed", c, t)
            _state.update { it.copy(searching = false, error = "Search failed: ${t.message}") }
        }
    }

    override fun onCleared() {
        recorder?.stop()
        voiceRecorder?.stop()
        asr?.close()
        embedder.close()
        store.close()
    }

    companion object {
        const val TAG = "App"
    }
}
