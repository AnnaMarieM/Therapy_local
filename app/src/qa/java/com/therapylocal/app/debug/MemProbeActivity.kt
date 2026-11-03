package com.therapylocal.app.debug

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.WindowManager
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.ModelRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * QA build only. Loads one Gemma build and writes exact memory counters after every step to files/out/memprobe_<tag>.json:
 *   adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.MemProbeActivity --es variant e4b --es backend GPU --ei ctx 6144 --ez audio false --ei calls 2 --es tag e4b_gpu
 * No patient data: the prompt is a made-up Czech transcript. Errors are written to the same file.
 */
class MemProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        thread(name = "memprobe") { run(i) }
    }

    private fun run(i: android.content.Intent) {
        val variant = GemmaVariant.entries.firstOrNull { it.key == i.getStringExtra("variant") } ?: GemmaVariant.E2B
        val order = (i.getStringExtra("backend") ?: "GPU").split(',')
        val ctx = i.getIntExtra("ctx", MemoryConfig.LLM_MAX_TOKENS)
        val audio = i.getBooleanExtra("audio", false)
        val calls = i.getIntExtra("calls", 2)
        val tag = i.getStringExtra("tag") ?: "${variant.key}_${order.joinToString("")}"
        val steps = JSONArray()
        val result = JSONObject().put("variant", variant.key).put("backend_order", order.joinToString(",")).put("ctx", ctx).put("audio", audio).put("steps", steps)
        val t0 = System.nanoTime()
        fun snap(name: String, extra: JSONObject = JSONObject()) {
            steps.put(snapshot(name, (System.nanoTime() - t0) / 1_000_000).also { s -> extra.keys().forEach { s.put(it, extra.get(it)) } })
            Log.i(TAG, "step=$name ${steps.getJSONObject(steps.length() - 1)}")
        }
        snap("start")
        val llm = MemoryLlm(this, ctx, ModelRegistry.gemma(variant))
        try {
            check(ModelRegistry.gemma(variant).isReady(this)) { "model file missing or incomplete" }
            val l0 = System.nanoTime()
            llm.load(order, withAudio = audio)
            snap("loaded", JSONObject().put("load_ms", (System.nanoTime() - l0) / 1_000_000).put("backend", llm.backend).put("has_audio", llm.hasAudio))
            repeat(calls) { n ->
                val c0 = System.nanoTime()
                val reply = llm.ask(SYSTEM, USER, null, 400, 0.1, 20)
                snap("call_${n + 1}", JSONObject().put("call_ms", (System.nanoTime() - c0) / 1_000_000).put("reply_chars", reply.length))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            result.put("error", JSONObject().put("type", t.javaClass.name).put("message", t.message?.take(600)))
            snap("failed")
        } finally {
            runCatching { llm.close() }
            snap("closed")
        }
        File(getExternalFilesDir(null), "out").apply { mkdirs() }.resolve("memprobe_$tag.json").writeText(result.toString(1))
        Log.i(TAG, "DONE tag=$tag")
        finish()
    }

    private fun snapshot(name: String, ms: Long): JSONObject {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val status = File("/proc/self/status").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim() }
        fun kb(key: String) = status[key]?.substringBefore(' ')?.toLongOrNull()
        val rollup = runCatching { File("/proc/self/smaps_rollup").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() } }.getOrDefault(emptyMap())
        return JSONObject()
            .put("step", name).put("t_ms", ms)
            .put("pss_total_kb", mi.totalPss).put("private_dirty_kb", mi.totalPrivateDirty)
            .put("native_pss_kb", mi.nativePss).put("graphics_kb", mi.getMemoryStat("summary.graphics")?.toLongOrNull())
            .put("vm_rss_kb", kb("VmRSS")).put("vm_hwm_kb", kb("VmHWM")).put("rss_file_kb", kb("RssFile")).put("rss_anon_kb", kb("RssAnon"))
            .put("smaps_pss_kb", rollup["Pss"]).put("smaps_pss_anon_kb", rollup["Pss_Anon"]).put("smaps_pss_file_kb", rollup["Pss_File"])
            .put("sys_avail_mb", sys.availMem shr 20).put("sys_total_mb", sys.totalMem shr 20).put("low_memory", sys.lowMemory)
    }

    companion object {
        const val TAG = "MEMPROBE"
        private const val SYSTEM = "You are a careful note-taker for a psychotherapist. List the facts the client stated, one JSON object per line with keys heading, content, evidence (an exact quote). Write heading and content in Czech."
        private const val USER = "Klient: Petra\n\nPetra: Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené. Usínám až po půlnoci a v pět ráno se budím. Pila jsem proto víc kávy, tři až čtyři hrnky denně. V pátek mi šéf řekl, že dostanu výpověď. Poslední den budu mít 30. září. Bojím se, jak zaplatím nájem, platím osm tisíc měsíčně. Chodím na dlouhé procházky podél řeky."
    }
}
