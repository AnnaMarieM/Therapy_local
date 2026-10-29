package com.therapylocal.app.memory

import android.os.SystemClock
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One "possibly relevant" fact. [score] is the smoothed cosine between the live audio window and the fact. [chunk] is the fact's transcript segment. */
data class Cue(val factId: String, val text: String, val heading: String, val session: String, val chunk: Int, val score: Float)

/**
 * Realtime fact cues during a session. The recorder feeds raw samples into [feed]; every [LiveCueSettings.intervalMs] the last
 * [LiveCueSettings.lookbackS] of audio are embedded DIRECTLY (EmbeddingGemma 2 audio, no transcript) and compared with the
 * embedded facts of one client. Scores are averaged over ticks per fact so the list does not jump, then the top [LiveCueConfig.TOP_K]
 * above the floor are published on [cues]. [settings] is read once per session, at [start].
 *
 * Never throws to the caller: failures land on [error] and the next tick retries. An unknown client or one without facts gives an empty list.
 * Audio stays in memory (a [LiveCueConfig.MAX_LOOKBACK_S] ring), nothing is written to disk and no text is logged, only fact ids and scores.
 */
class LiveCues(
    private val embedder: Embedder,
    private val store: MindStore,
    private val settings: () -> LiveCueSettings = { LiveCueSettings.DEFAULT },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    private val _running = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    private val ring = ShortArray(LiveCueConfig.MAX_LOOKBACK_SAMPLES)
    private var ringPos = 0
    private var ringFilled = 0

    private var job: Job? = null
    private var client: String? = null
    private var tick = 0

    /** Bumped by every start and stop. A tick that was already running when the session changed sees a different value and publishes nothing. */
    @Volatile
    private var generation = 0

    /** Hand this to [com.therapylocal.app.audio.ChunkRecorder.tap]. Cheap; ignored while not running. */
    fun feed(buf: ShortArray, n: Int) {
        if (!_running.value) return
        synchronized(ring) {
            var off = 0
            while (off < n) {
                val take = minOf(n - off, ring.size - ringPos)
                System.arraycopy(buf, off, ring, ringPos, take)
                ringPos = (ringPos + take) % ring.size
                off += take
            }
            ringFilled = minOf(ring.size, ringFilled + n)
        }
    }

    /** Idempotent: the same client keeps running untouched, another client restarts from an empty window. */
    @Synchronized
    fun start(client: String) {
        if (job?.isActive == true && this.client == client) return
        stop()
        this.client = client
        clearRing()
        val gen = ++generation
        tick = 0
        _error.value = null
        _cues.value = emptyList()
        _running.value = true
        val s = settings()
        AppLog.i(
            TAG, "live_cues_start", LogCtx.NONE, "client" to client, "lookback_s" to s.lookbackS, "interval_ms" to s.intervalMs,
            "smoothing" to s.smoothing, "score_floor" to s.scoreFloor,
        )
        job = scope.launch { loop(client, gen, s, HashMap()) }
    }

    @Synchronized
    fun stop() {
        val was = _running.value
        _running.value = false
        job?.cancel()
        job = null
        client = null
        generation++
        clearRing()
        _cues.value = emptyList()
        if (was) AppLog.i(TAG, "live_cues_stop", LogCtx.NONE, "ticks" to tick)
    }

    /** Forgets the audio: position reset and the samples overwritten, so nothing of the conversation stays in memory after a session. */
    private fun clearRing() = synchronized(ring) {
        ring.fill(0)
        ringPos = 0
        ringFilled = 0
    }

    private suspend fun loop(client: String, gen: Int, s: LiveCueSettings, smoothed: HashMap<Long, Float>) {
        var last = 0L
        while (gen == generation) {
            val t0 = SystemClock.elapsedRealtime()
            // Embedding can take longer than the interval, so the real interval is logged per tick.
            val since = if (last == 0L) 0L else t0 - last
            last = t0
            try {
                tick(client, gen, s, since, smoothed)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
                AppLog.e(TAG, "live_cue_tick_failed", LogCtx.NONE, t)
            }
            val spent = SystemClock.elapsedRealtime() - t0
            delay((s.intervalMs - spent).coerceAtLeast(LiveCueConfig.MIN_GAP_MS))
        }
    }

    /** The newest [samples] of the ring, or all of it when less was recorded. */
    private fun snapshot(samples: Int): ShortArray? = synchronized(ring) {
        if (ringFilled < LiveCueConfig.MIN_AUDIO_SAMPLES) return null
        val n = minOf(ringFilled, samples)
        val out = ShortArray(n)
        val start = (ringPos - n + ring.size) % ring.size
        val first = minOf(n, ring.size - start)
        System.arraycopy(ring, start, out, 0, first)
        System.arraycopy(ring, 0, out, first, n - first)
        out
    }

    private suspend fun tick(client: String, gen: Int, s: LiveCueSettings, sinceMs: Long, smoothed: HashMap<Long, Float>) {
        val pcm = snapshot(s.lookbackSamples) ?: return
        val rms = Pcm.rms(pcm)
        val n = ++tick
        if (rms < LiveCueConfig.SILENCE_RMS) {
            AppLog.d(TAG, "live_cue_silence", LogCtx.NONE, "tick" to n, "rms" to "%.4f".format(rms))
            return
        }
        AppLog.span(TAG, "live_cue_tick", LogCtx.NONE, "tick" to n, "client" to client, "interval_ms" to sinceMs, "audio_sec" to "%.2f".format(Pcm.seconds(pcm)), "rms" to "%.4f".format(rms)) { span ->
            val t0 = SystemClock.elapsedRealtime()
            val vec = embedder.embedAudio(pcm, LogCtx.NONE, role = "live_cue")
            val t1 = SystemClock.elapsedRealtime()
            val scored = withContext(Dispatchers.IO) { store.nearestFacts(client, vec, Int.MAX_VALUE) }
            val t2 = SystemClock.elapsedRealtime()

            val a = s.smoothing
            val now = scored.map { (f, raw) ->
                val prev = smoothed[f.id]
                val sm = if (prev == null) raw else a * raw + (1 - a) * prev
                smoothed[f.id] = sm
                Triple(f, sm, raw)
            }
            val top = now.sortedByDescending { it.second }.filter { it.second >= s.scoreFloor }.take(LiveCueConfig.TOP_K)
            // A stopped or restarted session must not publish a stale tick.
            if (gen == generation) {
                _cues.value = top.map { (f, sm, _) -> Cue(f.id.toString(), f.content, f.heading, f.sessionId, f.segIdx, sm) }
                _error.value = null
            }
            span.put("embed_ms", t1 - t0)
            span.put("search_ms", t2 - t1)
            span.put("facts", scored.size)
            span.put("shown", top.size)
            // ids and scores only, never text. raw = this tick's cosine, sm = smoothed
            span.put("top", top.joinToString(",") { "${it.first.id}:raw=${"%.3f".format(it.third)}/sm=${"%.3f".format(it.second)}" })
            span.put("raw_max", scored.maxOfOrNull { it.second }?.let { "%.3f".format(it) } ?: "-")
            span.put("raw_min", scored.minOfOrNull { it.second }?.let { "%.3f".format(it) } ?: "-")
        }
    }

    companion object {
        const val TAG = "LiveCues"
    }
}
