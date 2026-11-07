package com.therapylocal.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * QA build only. Loads EmbeddingGemma (text only) on the backends asked for, embeds a few made-up texts and writes load time, per-call time and the
 * cosine similarities to files/out/embedprobe_<tag>.json. Compare an NPU run with a CPU run: the vectors must agree.
 *   adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.EmbedProbeActivity --es backend NPU --ez force true --es tag npu
 * No patient data.
 */
class EmbedProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        thread(name = "embedprobe") { run(i) }
    }

    private fun run(i: android.content.Intent) {
        val backend = i.getStringExtra("backend") ?: "CPU"
        val force = i.getBooleanExtra("force", false)
        val tag = i.getStringExtra("tag") ?: backend
        val repeats = i.getIntExtra("repeats", 5)
        val result = JSONObject().put("backend_asked", backend).put("force", force)
        val calls = JSONArray()
        if (force) { BackendPolicy.override = backend; BackendPolicy.force = true }
        val embedder = Embedder(this, textOnly = true)
        try {
            val t0 = System.nanoTime()
            runBlocking { embedder.load() }
            result.put("backend", embedder.backend).put("load_ms", (System.nanoTime() - t0) / 1_000_000)
            val texts = listOf(
                "Moje sestra se přestěhovala do Brna a jsme si vzdálené.",
                "My sister moved to another city and we have grown apart.",
                "V pátek mi šéf řekl, že dostanu výpověď.",
                "Chodím na dlouhé procházky podél řeky.",
            )
            val vecs = texts.map { t ->
                val c0 = System.nanoTime()
                val v = runBlocking { embedder.embedText(t, false, LogCtx.NONE) }
                calls.put((System.nanoTime() - c0) / 1_000_000)
                v
            }
            repeat(repeats) {
                val c0 = System.nanoTime()
                runBlocking { embedder.embedText(texts[0], false, LogCtx.NONE) }
                calls.put((System.nanoTime() - c0) / 1_000_000)
            }
            fun cos(a: FloatArray, b: FloatArray): Double {
                var d = 0.0; var na = 0.0; var nb = 0.0
                for (k in a.indices) { d += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k] }
                return d / (sqrt(na) * sqrt(nb))
            }
            result.put("cos_0_1", cos(vecs[0], vecs[1])).put("cos_0_2", cos(vecs[0], vecs[2])).put("cos_0_3", cos(vecs[0], vecs[3]))
            result.put("dim", vecs[0].size).put("head", JSONArray(vecs[0].take(8).map { it.toDouble() }))
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            result.put("error", JSONObject().put("type", t.javaClass.name).put("message", t.message?.take(600)))
        } finally {
            BackendPolicy.force = false; BackendPolicy.override = "auto"
            runCatching { embedder.close() }
        }
        result.put("call_ms", calls)
        File(getExternalFilesDir(null), "out").apply { mkdirs() }.resolve("embedprobe_$tag.json").writeText(result.toString(1))
        Log.i(TAG, "DONE tag=$tag $result")
        finish()
    }

    companion object { const val TAG = "EMBEDPROBE" }
}
