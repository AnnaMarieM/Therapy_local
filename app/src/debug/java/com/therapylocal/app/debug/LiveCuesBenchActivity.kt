package com.therapylocal.app.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.LiveCues
import com.therapylocal.app.memory.MemoryStore
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug only. Replays `files/test/<file>` at real-time pace (times `speed`) through the real recorder tap into [LiveCues] for one client,
 * and writes what the cue list looked like every second to `files/live_cues/<file>.jsonl` (fact ids and scores only).
 *
 *   adb shell am start -n com.therapylocal.app/.debug.LiveCuesBenchActivity --es client george --es file george_s01.wav [--ef speed 1.0]
 *
 * Per-tick timings (embed_ms, search_ms) are in the app log as `live_cue_tick`. Run it with the main screen closed so only one embedder is loaded.
 */
class LiveCuesBenchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { setPadding(32, 64, 32, 32) }
        setContentView(status)
        if (!Vault.isUnlocked) {
            status.text = "Unlock MindVault first, then rerun the bench."
            AppLog.w("LiveCuesBench", "bench_vault_locked")
            return
        }
        val client = intent.getStringExtra("client") ?: "george"
        val file = intent.getStringExtra("file") ?: error("--es file <name.wav> is required")
        val speed = intent.getFloatExtra("speed", 1f)
        scope.launch {
            val out = File(getExternalFilesDir(null), "live_cues").apply { mkdirs() }.resolve("$file.jsonl").apply { writeText("") }
            val say = { m: String -> runOnUiThread { status.text = m } }
            try {
                say("loading embedder")
                val embedder = Embedder(applicationContext).also { it.load() }
                val store = MemoryStore(applicationContext)
                val (chunks, facts) = store.counts(client)
                AppLog.i("LiveCuesBench", "bench_start", LogCtx.NONE, "client" to client, "file" to file, "speed" to speed, "facts" to facts, "backend" to embedder.backend)
                val pcm = TestAudio.load(applicationContext, file)
                val cues = LiveCues(embedder, store)
                val rec = ChunkRecorder.recording(LogCtx.NONE)
                rec.tap = cues::feed
                cues.start(client)
                rec.startFromPcm(pcm, speed)
                val t0 = System.nanoTime()
                var last = ""
                while (!rec.finished) {
                    delay(1000)
                    val now = cues.cues.value
                    val key = now.joinToString(",") { it.factId }
                    val sec = (System.nanoTime() - t0) / 1e9
                    if (key != last) {
                        last = key
                        out.appendText(JSONObject().put("t", "%.1f".format(sec)).put("error", cues.error.value ?: JSONObject.NULL)
                            .put("cues", JSONArray(now.map { JSONObject().put("id", it.factId).put("cat", it.category).put("score", "%.3f".format(it.score)) })).toString() + "\n")
                    }
                    say("t=%.0fs of %.0fs · %d cues · facts=%d chunks=%d%s".format(sec, pcm.size / 16000.0 / speed, now.size, facts, chunks, cues.error.value?.let { " · ERR $it" } ?: ""))
                }
                cues.stop()
                rec.stop()
                embedder.close()
                File(out.parentFile, "$file.DONE").writeText("ok")
                say("done")
            } catch (t: Throwable) {
                AppLog.e("LiveCuesBench", "bench_failed", LogCtx.NONE, t)
                File(out.parentFile, "$file.ERROR").writeText(t.stackTraceToString())
                say("FAILED ${t.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
