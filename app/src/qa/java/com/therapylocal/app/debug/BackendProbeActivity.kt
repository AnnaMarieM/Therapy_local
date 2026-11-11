package com.therapylocal.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.embed.EmbedGolden
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * QA build only. Loads the EmbeddingGemma embedder or the Canary recogniser through the REAL ladder, in the engine processes, on one chosen
 * backend, runs a short deterministic workload and writes files/out/backendprobe_<tag>.json. Then it does the same on the CPU as the
 * reference, so the file says how far the backend's output is from the CPU's.
 *
 *   adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.BackendProbeActivity \
 *     --es model embed|embed_audio|canary --es backend GPU|NPU|NNAPI|CPU [--ez force true] [--es tag t] [--ei calls 3] [--ez golden true] [--ei tokens 2048] [--ez reset true]
 *
 * `force` skips the guard's blocks (as MemProbeActivity does; the engine process may die, main must not). Without
 * it the unforced ladder decides. The reference run is always forced onto the CPU. Embeddings: cosine to the CPU vector per fixed input
 * ([EmbedGolden]) and per-call milliseconds. Canary: a synthetic 8 s clip, the text length and whether the text equals the CPU's. With
 * `--ez golden true` (use it with backend CPU) the CPU vectors are also written in the format of assets/embed_golden.json, to be copied
 * into the app. No patient data: every input is fixed or synthetic.
 */
class BackendProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        thread(name = "backendprobe") { run(i) }
    }

    private class Run(val backend: String, val loadMs: Long, val usedBackend: String, val calls: Map<String, MutableList<Long>>, val vectors: Map<String, FloatArray>, val text: String?)

    /** The input size asked for with `--ei tokens`; null leaves the app's own choice (from the Settings lookback). */
    private var tokens: Int? = null

    private fun run(i: Intent) {
        val model = i.getStringExtra("model") ?: "embed"
        val backend = (i.getStringExtra("backend") ?: "GPU").uppercase()
        val force = i.getBooleanExtra("force", false)
        val calls = i.getIntExtra("calls", 3)
        val golden = i.getBooleanExtra("golden", false)
        if (i.getBooleanExtra("reset", false)) com.therapylocal.app.models.Backends.reset(this)
        tokens = if (i.hasExtra("tokens")) i.getIntExtra("tokens", 0) else null
        val tag = i.getStringExtra("tag") ?: "${model}_$backend"
        val result = JSONObject().put("model", model).put("backend_asked", backend).put("forced", force).put("calls", calls).put("tokens", tokens)
        try {
            val first = workload(model, backend, force, calls)
            result.put("run", describe(first))
            if (backend != "CPU") {
                val ref = workload(model, "CPU", true, calls)
                result.put("cpu_reference", describe(ref))
                val cos = JSONObject()
                first.vectors.forEach { (k, v) -> ref.vectors[k]?.let { cos.put(k, EmbedGolden.cosine(v, it).toDouble()) } }
                if (cos.length() > 0) result.put("cosine_vs_cpu", cos)
                if (first.text != null) result.put("text_equals_cpu", first.text == ref.text)
                result.put("median_ms_vs_cpu", JSONObject().also { o -> first.calls.forEach { (k, v) -> o.put(k, JSONObject().put("backend", median(v)).put("cpu", median(ref.calls[k].orEmpty())) ) } })
            } else if (golden) {
                result.put("golden", goldenJson(first))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            result.put("error", JSONObject().put("type", t.javaClass.name).put("message", t.message?.take(600)))
        } finally {
            BackendPolicy.force = false; BackendPolicy.override = "auto"
        }
        File(getExternalFilesDir(null), "out").apply { mkdirs() }.resolve("backendprobe_$tag.json").writeText(result.toString(1))
        Log.i(TAG, "DONE tag=$tag")
        finish()
    }

    private fun workload(model: String, backend: String, force: Boolean, calls: Int): Run {
        BackendPolicy.override = if (force || backend == "CPU") backend else "auto"
        BackendPolicy.force = force
        return if (model == "canary") canary(backend, calls) else embed(model == "embed", backend, calls)
    }

    private fun embed(textOnly: Boolean, backend: String, calls: Int): Run {
        val e = tokens.let { t -> if (t == null) Embedder(this, textOnly = textOnly) else Embedder(this, textOnly = textOnly, inputTokens = { t }) }
        val ms = linkedMapOf<String, MutableList<Long>>()
        val vecs = linkedMapOf<String, FloatArray>()
        try {
            val t0 = System.nanoTime()
            runBlocking { e.load() }
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val texts = listOf("query" to EmbedGolden.QUERY, "related" to EmbedGolden.RELATED, "unrelated" to EmbedGolden.UNRELATED)
            repeat(calls) {
                for ((name, text) in texts) {
                    val c0 = System.nanoTime()
                    val v = runBlocking { e.embedText(text, name == "query", LogCtx.NONE) }
                    ms.getOrPut("text") { mutableListOf() } += (System.nanoTime() - c0) / 1_000_000
                    vecs[name] = v
                }
                if (!textOnly) {
                    val c0 = System.nanoTime()
                    val v = runBlocking { e.embedAudio(EmbedGolden.tone(), LogCtx.NONE, "document") }
                    ms.getOrPut("audio_1s") { mutableListOf() } += (System.nanoTime() - c0) / 1_000_000
                    vecs["tone"] = v
                }
            }
            return Run(backend, loadMs, e.backend, ms, vecs, null)
        } finally {
            runCatching { e.close() }
            Thread.sleep(1500) // let the host finish the close before the next load measures free memory
        }
    }

    private fun canary(backend: String, calls: Int): Run {
        val asr = SpeechRecogniser(this, SpeechLanguage.English, provider = backend.lowercase())
        val ms = linkedMapOf<String, MutableList<Long>>()
        try {
            val t0 = System.nanoTime()
            runBlocking { asr.load() }
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val pcm = synthetic(8)
            var text = ""
            repeat(calls) {
                val c0 = System.nanoTime()
                text = runBlocking { asr.transcribe(pcm, LogCtx.NONE) }.text
                ms.getOrPut("transcribe_8s") { mutableListOf() } += (System.nanoTime() - c0) / 1_000_000
            }
            return Run(backend, loadMs, asr.backend, ms, emptyMap(), text)
        } finally {
            runCatching { asr.close() }
            Thread.sleep(1500)
        }
    }

    /** A fixed speech-like signal: a few tones whose amplitude is shaped into syllables. Not intelligible, but deterministic. */
    private fun synthetic(seconds: Int): ShortArray = ShortArray(16000 * seconds) { n ->
        val t = n / 16000.0
        val env = 0.5 + 0.5 * sin(2 * Math.PI * 3.5 * t)
        (6000 * env * (sin(2 * Math.PI * 180 * t) + 0.5 * sin(2 * Math.PI * 720 * t) + 0.25 * sin(2 * Math.PI * 1800 * t))).toInt().toShort()
    }

    private fun describe(r: Run) = JSONObject()
        .put("backend_requested", r.backend).put("backend_used", r.usedBackend).put("open_ms", r.loadMs)
        .put("calls_ms", JSONObject().also { o -> r.calls.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        .also { o -> r.text?.let { o.put("text_chars", it.length) } }

    private fun median(v: List<Long>) = if (v.isEmpty()) -1 else v.sorted()[v.size / 2]

    private fun goldenJson(r: Run): JSONObject {
        val sha = com.therapylocal.app.models.ModelRegistry.EMBED.files.first().sha256
        val vs = JSONObject()
        r.vectors.forEach { (k, v) -> vs.put(k, JSONArray().also { a -> v.forEach { x -> a.put(Math.round(x * 1e5) / 1e5) } }) }
        return JSONObject().put("sha256", sha).put("vectors", vs)
    }

    companion object { const val TAG = "BACKENDPROBE" }
}
