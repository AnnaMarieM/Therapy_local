package com.therapylocal.app.qaapi

import android.content.Context
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.asr.SpeakerDiarizer
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.engine.LabWiring
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** The backend asked for in a call: "auto" (the ladder of AD-08/AD-09 decides) or one accelerator id; [force] skips the guard's blocks, as the QA probes do. */
data class BackendChoice(val backend: String, val force: Boolean) {
    val auto: Boolean get() = backend == "auto"

    companion object {
        val KNOWN = setOf("auto", "cpu", "gpu", "npu", "nnapi")

        fun parse(a: JSONObject): BackendChoice {
            val b = QaArgs.optString(a, "backend")?.lowercase() ?: "auto"
            if (b !in KNOWN) throw QaException(QaCode.BAD_REQUEST, "backend must be one of ${KNOWN.joinToString(",")}")
            return BackendChoice(b, a.optBoolean("force", false))
        }
    }

    /** Sets [BackendPolicy] for the duration of [block] and puts it back: no new gate, the engines' own ladder and logging do the rest. */
    fun <T> applied(block: () -> T): T {
        val (o, f) = BackendPolicy.override to BackendPolicy.force
        BackendPolicy.override = backend.uppercase().takeIf { !auto } ?: "auto"
        BackendPolicy.force = force && !auto
        try { return block() } finally { BackendPolicy.override = o; BackendPolicy.force = f }
    }
}

/**
 * The model functions as API calls: transcribe, diarize, embed, ask. Each builds the same engine class the pipeline builds (SpeechRecogniser,
 * SpeakerDiarizer, Embedder, MemoryLlm, via the engine hosts and the backend ladder), runs one call, closes it, and returns the result with
 * its timings: `load_ms`, `dur_ms`, the backend actually used, the model id and, where audio is involved, `rtf` (wall time over audio time).
 * One engine at a time: the caller checks that the job queue is idle ([QaAppBackend.requireIdle]).
 */
class QaEngines(private val app: Context) {
    private fun pcm(file: File, startS: Double, durS: Double?): ShortArray {
        val all = try { TestAudio.load(file) } catch (_: IllegalArgumentException) { throw QaException(QaCode.BAD_REQUEST, "not a readable WAV file") }
        val from = (startS * Pcm.SAMPLE_RATE).toInt().coerceIn(0, all.size)
        val to = if (durS == null) all.size else (from + durS * Pcm.SAMPLE_RATE).toInt().coerceAtMost(all.size)
        return all.copyOfRange(from, to)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    fun transcribe(
        file: File, language: String, startS: Double, durS: Double?, choice: BackendChoice,
        /** "canary" (default) or "whisper" (Whisper large-v3 q8_0 through whisper.cpp, CPU only, AD-22). */
        engine: String = "canary", translate: Boolean = false, beam: Int = 5,
    ): JSONObject {
        val clip = pcm(file, startS, durS)
        if (clip.size > LiveCueConfig.MAX_LOOKBACK_SAMPLES) throw QaException(QaCode.BAD_REQUEST, "clip too long: at most ${LiveCueConfig.MAX_LOOKBACK_SAMPLES / Pcm.SAMPLE_RATE} s per call (use start_s and dur_s)")
        return choice.applied {
            val whisper = engine == "whisper"
            val spec = if (whisper) ModelRegistry.WHISPER_LARGE_V3_Q8 else ModelRegistry.CANARY_1B_V2
            val asr = SpeechRecogniser(app, SpeechLanguage.of(language), provider = if (choice.auto) "cpu" else choice.backend, modelSpec = spec, translate = translate, beam = if (whisper) beam else 0)
            try {
                val t0 = System.nanoTime()
                runBlocking { asr.load() }
                val loadMs = ms(t0)
                val r = runBlocking { asr.transcribe(clip, LogCtx.NONE) }
                done("transcribe", asr.backend, clip.size, r.wallMs)
                JSONObject().put("text", r.text).put("audio_sec", r.audioSec).put("load_ms", loadMs).put("dur_ms", r.wallMs)
                    .put("rtf", rtf(r.wallMs, r.audioSec)).put("backend", asr.backend).put("backend_requested", choice.backend).put("model", spec.id)
                    .put("task", if (!whisper) JSONObject.NULL else if (translate) "translate" else "transcribe")
            } finally { runCatching { asr.close() } }
        }
    }

    fun diarize(file: File, speakers: Int, choice: BackendChoice): JSONObject {
        val all = pcm(file, 0.0, null)
        return choice.applied {
            val d = SpeakerDiarizer(app)
            try {
                val t0 = System.nanoTime()
                runBlocking { d.load() }
                val loadMs = ms(t0)
                val c0 = System.nanoTime()
                val turns = runBlocking { d.diarize(all, speakers, LogCtx.NONE) }
                val dur = ms(c0)
                done("diarize", d.backend, all.size, dur)
                val sec = Pcm.seconds(all)
                JSONObject().put("turns", JSONArray(turns.map { JSONObject().put("start_ms", it.startMs).put("end_ms", it.endMs).put("speaker", it.speaker) }))
                    .put("speakers", turns.map { it.speaker }.distinct().size).put("audio_sec", sec).put("load_ms", loadMs).put("dur_ms", dur).put("rtf", rtf(dur, sec))
                    .put("backend", d.backend).put("backend_requested", choice.backend).put("model", ModelRegistry.DIARIZATION.id)
            } finally { runCatching { d.close() } }
        }
    }

    fun embed(texts: List<String>, query: Boolean, withVectors: Boolean, choice: BackendChoice): JSONObject = choice.applied {
        val e = Embedder(app, textOnly = true)
        try {
            val t0 = System.nanoTime()
            runBlocking { e.load() }
            val loadMs = ms(t0)
            val out = JSONArray()
            var total = 0L
            for (t in texts) {
                val c0 = System.nanoTime()
                val v = runBlocking { e.embedText(t, query, LogCtx.NONE) }
                val d = ms(c0); total += d
                val o = JSONObject().put("dim", v.size).put("norm", sqrt(v.sumOf { (it * it).toDouble() })).put("dur_ms", d)
                if (withVectors) o.put("vector", JSONArray(v.map { it.toDouble() }))
                out.put(o)
            }
            done("embed", e.backend, 0, total)
            JSONObject().put("embeddings", out).put("load_ms", loadMs).put("dur_ms", total).put("backend", e.backend).put("backend_requested", choice.backend).put("model", EmbeddingConfig.MODEL_ID)
        } finally { runCatching { e.close() } }
    }

    fun ask(
        system: String, user: String, schema: String?, maxTokens: Int, temperature: Double, topK: Int, choice: BackendChoice,
        audio: File? = null, startS: Double = 0.0, durS: Double? = null,
    ): JSONObject = onWorkerThread { choice.applied {
        val spec = LabWiring.spec(ModelSetup.gemma(app))
        val clip = audio?.let { pcm(it, startS, durS) }
        if (clip != null && clip.size > MAX_LISTEN_SAMPLES) throw QaException(QaCode.BAD_REQUEST, "clip too long: Gemma hears at most 30 s per call (use start_s and dur_s)")
        val llm = MemoryLlm(app, MemoryConfig.LLM_MAX_TOKENS, spec, withAudio = clip != null)
        try {
            val t0 = System.nanoTime()
            llm.load(respectBlocks = !choice.force)
            val loadMs = ms(t0)
            val c0 = System.nanoTime()
            val text = if (clip != null) llm.listen(system, user, clip, maxTokens, temperature, topK) else llm.ask(system, user, schema, maxTokens, temperature, topK)
            val dur = ms(c0)
            done(if (clip != null) "listen" else "ask", llm.backend, clip?.size ?: 0, dur)
            JSONObject().put("text", text).put("chars", text.length).put("load_ms", loadMs).put("dur_ms", dur).put("backend", llm.backend)
                .put("backend_requested", choice.backend).put("model", spec.id)
                .also { o -> if (clip != null) { val sec = Pcm.seconds(clip); o.put("audio_sec", sec).put("rtf", rtf(dur, sec)) } }
        } finally { runCatching { llm.close() } }
    } }

    /** The engine proxy refuses a call on a binder thread (the QA API is called on one), so the model call runs on a worker and the binder thread waits for it. */
    private fun <T> onWorkerThread(block: () -> T): T = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() } }

    private companion object { const val MAX_LISTEN_SAMPLES = 30 * Pcm.SAMPLE_RATE }

    private fun rtf(wallMs: Long, audioSec: Double) = if (audioSec > 0) wallMs / 1000.0 / audioSec else JSONObject.NULL

    // Counts, times and the backend only: never the text.
    private fun done(what: String, backend: String, samples: Int, wallMs: Long) =
        AppLog.i(QaAppBackend.TAG, "engine_call", LogCtx.NONE, "op" to what, "backend" to backend, "audio_sec" to "%.2f".format(samples.toDouble() / Pcm.SAMPLE_RATE), "wall_ms" to wallMs)
}
