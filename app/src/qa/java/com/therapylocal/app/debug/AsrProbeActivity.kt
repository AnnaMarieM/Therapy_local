package com.therapylocal.app.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * QA build only. Loads Canary on one provider through the real ladder and the real `:batch` engine host, transcribes the 16 kHz mono wavs in
 * files/eval (public FLEURS clips, never patient data) and writes files/out/asrprobe_<tag>.json: backend the ladder reported, load time,
 * per clip text and real-time factor.
 *   adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.AsrProbeActivity --es provider nnapi --es lang cs --es tag nnapi_cs
 * `provider` empty or "auto" takes the ladder's own order; any other value is asked for first and forced (no guard blocks).
 */
class AsrProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        thread(name = "asrprobe") { run(i) }
    }

    private fun run(i: android.content.Intent) {
        val provider = i.getStringExtra("provider") ?: "auto"
        val lang = SpeechLanguage.of(i.getStringExtra("lang") ?: "cs")
        val tag = i.getStringExtra("tag") ?: "${provider}_${lang.code}"
        val maxClips = i.getIntExtra("clips", 6)
        val result = JSONObject().put("provider", provider).put("lang", lang.code)
        val clips = JSONArray()
        result.put("clips", clips)
        val auto = provider == "auto" || provider.isEmpty()
        if (!auto) { BackendPolicy.override = provider.uppercase(); BackendPolicy.force = true }
        val dir = File(getExternalFilesDir(null), "eval")
        val asr = if (auto) SpeechRecogniser(this, lang) else SpeechRecogniser(this, lang, provider = provider)
        try {
            val t0 = SystemClock.elapsedRealtime()
            runBlocking { asr.load() }
            result.put("backend", asr.backend).put("load_ms", SystemClock.elapsedRealtime() - t0)
            val wavs = dir.listFiles { f -> f.extension == "wav" && f.name.startsWith(lang.code + "_") }?.sortedBy { it.name }.orEmpty().take(maxClips)
            var decode = 0L; var audio = 0.0
            for (w in wavs) {
                val pcm = readWav(w)
                val r = runBlocking { asr.transcribe(pcm.copyOf(minOf(pcm.size, 20 * 16_000)), LogCtx.NONE) }
                decode += r.wallMs; audio += r.audioSec
                clips.put(JSONObject().put("file", w.name).put("text", r.text).put("wall_ms", r.wallMs).put("audio_s", r.audioSec))
            }
            result.put("decode_ms", decode).put("audio_s", audio).put("rtf", if (audio > 0) decode / 1000.0 / audio else -1.0)
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            result.put("error", t.javaClass.name + ": " + t.message?.take(600))
        } finally {
            BackendPolicy.force = false; BackendPolicy.override = "auto"
            runCatching { asr.close() }
        }
        File(getExternalFilesDir(null), "out").apply { mkdirs() }.resolve("asrprobe_$tag.json").writeText(result.toString(1))
        Log.i(TAG, "DONE tag=$tag backend=${result.optString("backend")} rtf=${result.optDouble("rtf")} load_ms=${result.optLong("load_ms")}")
        finish()
    }

    private fun readWav(f: File): ShortArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4)
            val len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(len.toLong() and 0xffffffffL, (b.size - p - 8).toLong()).toInt() / 2
                val s = ShortArray(n)
                ByteBuffer.wrap(b, p + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                return s
            }
            p += 8 + len
        }
        error("no data chunk in ${f.name}")
    }

    companion object { const val TAG = "ASRPROBE" }
}
