package com.therapylocal.app.engine

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug only, DUMP-protected (the shell holds it; a third-party app cannot get it). Starts a lab scenario in the real app process with
 * the fakes and a fault plan, for host scripts (qa/stress) and rows that need a non-instrumented main:
 *
 *   am start -W -n com.therapylocal.app/.engine.EngineLabActivity --es scenario processing|live|search --es plan '<plan json>'
 *            [--ez fgs true] [--ez background true] [--es canary TEXT] [--es action vault_lock]
 *
 * scenario  runs on worker threads that outlive the activity, until the process ends: processing = fake LLM asks, text embedding and
 *           transcription through the `:batch` host in rounds; live = audio embedding every 2 s through `:live`; search = text queries.
 *           It uses the engines directly (no database); an error is logged as `lab_scenario_error` with its type and the loop goes on.
 * plan      a fault plan (catalogue 2.3), installed with [LabWiring.install]; a bad plan is logged and nothing starts.
 * fgs       holds a foreground service, so the app stays at foreground-service priority.
 * background  finishes the activity at once, so the app is in the background while the scenario runs.
 * canary    text that goes into every prompt and query, for the "no patient text in the logs" rows.
 * action    `vault_lock` calls the real Vault.lock() path (wipe, relaunch, kill the process).
 */
class EngineLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val status = TextView(this).apply { setPadding(32, 64, 32, 32) }
        setContentView(status)
        val i = intent
        if (i.getStringExtra("action") == "vault_lock") {
            AppLog.i(TAG, "lab_vault_lock", LogCtx.NONE)
            AppLog.flush()
            Vault.lock(this)
            return
        }
        val scenario = i.getStringExtra("scenario")
        if (scenario == null) {
            status.text = "engine lab: nothing to do"
            return
        }
        try {
            LabWiring.install(i.getStringExtra("plan"))
        } catch (e: FaultPlanException) {
            AppLog.e(TAG, "lab_plan_rejected", LogCtx.NONE, e)
            status.text = "engine lab: plan rejected"
            return
        }
        status.text = "engine lab: $scenario"
        AppLog.i(TAG, "lab_start", LogCtx.NONE, "scenario" to scenario, "fgs" to i.getBooleanExtra("fgs", false), "background" to i.getBooleanExtra("background", false))
        if (i.getBooleanExtra("fgs", false)) app.startForegroundService(Intent(app, EngineLabService::class.java))
        Scenarios.start(app, scenario, i.getStringExtra("canary").orEmpty())
        if (i.getBooleanExtra("background", false)) finish()
    }

    private companion object {
        const val TAG = "EngineLab"
    }
}

/** The lab's foreground service (dataSync, like the memory service). Debug only. */
class EngineLabService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("engine_lab", "Engine lab", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "engine_lab").setContentTitle("Engine lab").setSmallIcon(android.R.drawable.stat_notify_sync).build()
        startForeground(7311, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The scenario loops. One thread per scenario name; starting a name again stops the old loop. */
internal object Scenarios {
    private const val TAG = "EngineLab"
    private val generation = HashMap<String, AtomicInteger>()

    @Synchronized
    fun start(app: Context, name: String, canary: String) {
        val mine = generation.getOrPut(name) { AtomicInteger() }.incrementAndGet()
        val alive = { generation[name]?.get() == mine }
        Thread({
            while (alive()) {
                try {
                    when (name) {
                        "processing" -> processing(app, canary, alive)
                        "live" -> live(app, alive)
                        "search" -> search(app, canary, alive)
                        else -> return@Thread
                    }
                } catch (t: Throwable) {
                    AppLog.w(TAG, "lab_scenario_error", LogCtx.NONE, "scenario" to name, "type" to t.javaClass.simpleName)
                }
                runCatching { Thread.sleep(500) }
            }
        }, "lab-$name").apply { isDaemon = true; start() }
    }

    private fun processing(app: Context, canary: String, alive: () -> Boolean) {
        val llm = MemoryLlm(app, 2048, LabWiring.spec(ModelSetup.gemma(app)))
        try {
            llm.load(order = listOf("GPU", "CPU"))
            repeat(3) { if (alive()) llm.ask("extract", "$canary session text $it", null, 200, 0.1, 10) }
        } finally {
            runCatching { llm.close() }
        }
        val embedder = com.therapylocal.app.embed.Embedder(app, textOnly = true)
        try {
            runBlocking { embedder.load(); embedder.embedText("$canary fact", false, LogCtx.NONE) }
        } finally {
            embedder.close()
        }
        val asr = SpeechRecogniser(app, SpeechLanguage.of("en"))
        try {
            runBlocking { asr.load(); asr.transcribe(ShortArray(16_000) { (it % 100).toShort() }, LogCtx.NONE) }
        } finally {
            asr.close()
        }
        Thread.sleep(1_000)
    }

    private fun live(app: Context, alive: () -> Boolean) {
        val e = Engines.embedder(app)
        runBlocking { e.load() }
        val tone = ShortArray(32_000) { (it % 200).toShort() }
        while (alive()) {
            runBlocking { e.embedAudio(tone, LogCtx.NONE) }
            Thread.sleep(2_000)
        }
    }

    private fun search(app: Context, canary: String, alive: () -> Boolean) {
        val e = Engines.embedder(app)
        runBlocking { e.load() }
        while (alive()) {
            runBlocking { e.embedText("$canary query", true, LogCtx.NONE) }
            Thread.sleep(1_000)
        }
    }
}
