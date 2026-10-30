package com.therapylocal.app.session

import android.content.Context
import android.os.SystemClock
import android.os.storage.StorageManager
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.Ids
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobRunner
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.memory.LiveCues
import com.therapylocal.app.memory.MemoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one session that can record at a time: its state, the microphone ([ChunkRecorder]), live cues and the [RecordingService] that
 * keeps the process alive while recording. It lives for the process, not for a screen, so leaving the app or rotating never stops a session.
 *
 * Commands return at once and run in order on [ops], so a quick start-pause-finish cannot race. The UI reads [state], [level] and [cues].
 * Call [init] once the vault is unlocked (it needs the store).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SessionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = Mutex()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Microphone loudness 0..1, about 30 updates a second while recording, 0 otherwise. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    private lateinit var app: Context
    private var store: MindStore? = null

    /** Created by [init]; exposed for the debug harness, which reads it like the session does. */
    lateinit var liveCues: LiveCues
        private set

    private var recorder: ChunkRecorder? = null
    private var lastLevelAt = 0L

    /** Idempotent. A reopened store (after an erase) gets fresh live cues bound to it. */
    @Synchronized
    fun init(context: Context) {
        app = context.applicationContext
        val s = MindStore.get(app)
        if (store === s) return
        store = s
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        liveCues = LiveCues(Engines.embedder(app), s) { prefs.liveCueSettings() }
        scope.launch { liveCues.cues.collect { _cues.value = it } }
    }

    /**
     * Starts a session for [client] and begins recording at once. [cues] says whether live cues can run now (the embedder is loaded);
     * [enableCues] turns them on later. False when a session is already running or still saving its last chunk.
     */
    fun start(client: String, cues: Boolean, now: Long = System.currentTimeMillis(), session: String = Ids.sessionId(client, now)): Boolean {
        if (!_state.value.canStart) return false
        // Shown as recording right away so the screen answers the gesture; rolled back if the microphone cannot open.
        _state.value = SessionState(SessionPhase.Recording, client, session, now, SessionClock.started(SystemClock.elapsedRealtime()), cuesOn = cues)
        RecordingService.start(app)
        scope.launch { ops.withLock { begin(client to session, cues) { it.start() } } }
        return true
    }

    /** Debug harness: a recording outside any session, from the microphone or replaying [pcm]. Its audio is kept but never processed. */
    fun startDebug(pcm: ShortArray? = null): Boolean {
        if (!_state.value.canStart) return false
        _state.value = SessionState(SessionPhase.Recording, clock = SessionClock.started(SystemClock.elapsedRealtime()))
        RecordingService.start(app)
        scope.launch { ops.withLock { begin(null, cues = false) { r -> if (pcm == null) r.start() else r.startFromPcm(pcm) } } }
        return true
    }

    fun pause() = command {
        if (_state.value.phase != SessionPhase.Recording) return@command
        recorder?.isPaused = true
        _level.value = 0f
        _state.update { it.copy(phase = SessionPhase.Paused, clock = it.clock.pause(SystemClock.elapsedRealtime())) }
    }

    fun resume() = command {
        if (_state.value.phase != SessionPhase.Paused) return@command
        recorder?.isPaused = false
        _state.update { it.copy(phase = SessionPhase.Recording, clock = it.clock.resume(SystemClock.elapsedRealtime())) }
    }

    /** Ends the session. Its recording is queued for processing once the last chunk is saved. */
    fun finish() = command {
        val s = _state.value
        if (!s.active) return@command
        liveCues.stop()
        _level.value = 0f
        val clock = s.clock.pause(SystemClock.elapsedRealtime())
        _state.update { it.copy(phase = SessionPhase.Finishing, clock = clock) }
        val rec = recorder
        if (rec == null) {
            // The microphone never opened; [begin] has already rolled the session back.
            _state.update { SessionState(error = it.error) }
            RecordingService.stop(app)
            return@command
        }
        withContext(Dispatchers.Default) { rec.stop() }
    }

    /** Live cues for the running session, once setup has finished during it. */
    fun enableCues() = command {
        val s = _state.value
        if (!s.active || s.cuesOn || s.client.isEmpty()) return@command
        liveCues.start(s.client)
        _state.update { it.copy(cuesOn = true) }
    }

    /** The "Saved" moment was seen (or its session discarded). */
    fun dismissEnded() = _state.update { it.copy(ended = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun command(run: suspend () -> Unit) {
        scope.launch { ops.withLock { run() } }
    }

    /** [owner] is (client, session), null for a debug recording. Runs under [ops]. */
    private suspend fun begin(owner: Pair<String, String>?, cues: Boolean, startSource: (ChunkRecorder) -> Unit) {
        val st = store ?: return fail(null, null, "The vault is locked.")
        // The audio stays on the phone until it is processed; a recording that cannot be saved is better not started.
        if (withContext(Dispatchers.IO) { freeBytes() } < ChunkConfig.MIN_FREE_BYTES) {
            return fail(null, null, "Not enough free storage to record. Free some space and try again.")
        }
        val engine = app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("engine", "whisper")!!
        val createdSession = owner?.let { (client, session) -> withContext(Dispatchers.IO) { !st.sessionExists(session).also { st.createSession(session, client, _state.value.startedAt) } } } ?: false
        val rid = withContext(Dispatchers.IO) { st.createRecording(owner?.second, engine, "") }
        val c = LogCtx(rid)
        val rec = ChunkRecorder.recording(c)
        rec.tap = { buf, n ->
            liveCues.feed(buf, n)
            val t = SystemClock.elapsedRealtime()
            if (t - lastLevelAt >= LEVEL_INTERVAL_MS) {
                lastLevelAt = t
                _level.value = AudioLevel.of(buf, n)
            }
        }
        if (cues && owner != null) liveCues.start(owner.first)
        try {
            startSource(rec)
        } catch (t: Throwable) {
            AppLog.e(TAG, "record_start_failed", c, t)
            liveCues.stop()
            // Nothing was recorded: an empty recording, or a session row with no audio, would be left behind otherwise.
            withContext(Dispatchers.IO) {
                st.deleteRecording(rid)
                if (createdSession) owner?.let { st.deleteSession(it.second) }
            }
            return fail(rid, owner, "The microphone could not be opened. ${t.message ?: ""}".trim())
        }
        recorder = rec
        JobRunner.recordingActive = true
        _state.update { it.copy(recordingId = rid, error = null) }
        scope.launch { drain(rid, rec, owner) }
    }

    private fun fail(rid: Long?, owner: Pair<String, String>?, message: String) {
        AppLog.w(TAG, "session_start_rolled_back", LogCtx(rid), "had_owner" to (owner != null))
        _state.value = SessionState(error = message)
        RecordingService.stop(app)
    }

    /** Saves chunks until the recorder closes, then hands the recording to the queue. */
    private suspend fun drain(rid: Long, rec: ChunkRecorder, owner: Pair<String, String>?) {
        val st = store ?: return
        try {
            saveChunks(st, rid, rec)
            liveCues.stop()
            // Only now, with the last chunk saved, is the recording handed to the queue (a deleted session has nothing to process).
            owner?.let { (_, session) -> withContext(Dispatchers.IO) { if (st.sessionExists(session)) JobGraph.enqueueRecording(Jobs.queue(app), session, rid) } }
        } finally {
            // The queue does not start new jobs while recording; anything that waited runs now.
            JobRunner.recordingActive = false
            MemoryService.resumePending(app)
        }
        ops.withLock {
            recorder = null
            val s = _state.value
            val ended = owner?.let { (client, session) -> EndedSession(client, session, s.startedAt, s.clock.elapsedMs(SystemClock.elapsedRealtime())) }
            _state.value = SessionState(error = s.error, ended = ended)
            RecordingService.stop(app)
        }
    }

    /** Every chunk is kept encrypted for the transcribe job, with a row that has no text yet. Nothing is transcribed while recording. */
    private suspend fun saveChunks(st: MindStore, rid: Long, rec: ChunkRecorder) {
        val c = LogCtx(rid)
        var saved = 0
        for (chunk in rec.chunks) {
            try {
                withContext(Dispatchers.IO) {
                    st.putChunk(ChunkRow(rid, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, null))
                    PendingAudio.save(app, rid, chunk)
                }
                saved++
            } catch (t: Throwable) {
                AppLog.e(TAG, "chunk_save_failed", c.chunk(chunk.idx), t, "code" to "IO")
                _state.update { it.copy(error = "Part of the recording could not be saved.") }
            }
        }
        AppLog.i(TAG, "recording_saved", c, "chunks" to saved)
    }

    /** Internal storage the app can still use, counting cache the system would clear for it. */
    private fun freeBytes(): Long {
        val storage = app.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(app.filesDir))
    }

    private const val TAG = "Session"
    private const val LEVEL_INTERVAL_MS = 33L
}
