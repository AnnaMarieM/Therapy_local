package com.therapylocal.app.qaapi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.log.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Debug-level temperature record, QA build only (the source set `qa`: the release, debug and feel builds do not contain this class, and
 * `verifyNoQaApiInRelease` fails if they ever do). Once a second while the job queue has a running job and the `thermal_log` setting is on
 * (default true), it writes `tag=Thermal evt=thermal_sample` at DEBUG level through [AppLog]: battery temperature, level, voltage, current, the
 * PowerManager thermal status and 10 s headroom, and the sysfs thermal zones an app may read (SELinux hides most of them from apps, see
 * `thermal_sensors_found`). Idle cost: one flow collector. Numbers and sensor names only, never text. The phone-side record of every sensor is
 * qa/thermal-log.sh.
 */
object QaThermalSampler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var kind: String? = null
    @Volatile private var loop: Job? = null
    @Volatile private var app: Context? = null
    private var zones: List<Pair<String, File>>? = null   // readable SoC temperature zones, probed once per process

    /** Called once from the provider, which Android creates at process start. */
    fun install(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch { Jobs.snapshot.map { it.running?.kind }.distinctUntilChanged().collect { kind = it; kick() } }
    }

    private fun enabled(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(QaThermal.SETTING_KEY, true)

    /** Start the loop if it should run and does not; also called when the setting changes. */
    fun kick() {
        val c = app ?: return
        synchronized(this) {
            if (loop?.isActive == true || kind == null || !enabled(c)) return
            loop = scope.launch { run(c) }
        }
    }

    private fun run(c: Context) {
        val summary = QaThermal.RunSummary()
        runCatching { logFound(c) }
        while (kind != null && enabled(c)) {
            val t0 = System.nanoTime()
            runCatching { sample(c) }.onSuccess { summary.add(it); AppLog.d(QaThermal.TAG, "thermal_sample", LogCtxNone, *QaThermal.fields(it).toTypedArray()) }
            val spent = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep((1000 - spent).coerceIn(50, 1000))
        }
        if (summary.seconds > 0) AppLog.d(QaThermal.TAG, "thermal_summary", LogCtxNone, *summary.fields().toTypedArray())
    }

    private val LogCtxNone = com.therapylocal.app.log.LogCtx.NONE

    private fun allZones(): List<File> = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 } ?: emptyList()

    private fun read(f: File): String? = try { f.readText().trim() } catch (_: Throwable) { null }

    /** Which zones this app can read, once per process: `thermal_sensors_found`, listing the SoC ones by name. Failures are counted, never logged one by one. */
    private fun logFound(c: Context) {
        val all = allZones()
        val readable = ArrayList<Pair<String, File>>(); var unreadable = 0; var notTemp = 0
        for (z in all) {
            val type = read(File(z, "type")) ?: z.name
            when {
                !QaThermal.isTemperatureZone(type) -> notTemp++
                QaThermal.celsius(read(File(z, "temp"))) == null -> unreadable++
                else -> readable += type to File(z, "temp")
            }
        }
        val first = zones == null
        zones = readable.filter { QaThermal.isSoc(it.first) }
        if (first) AppLog.d(QaThermal.TAG, "thermal_sensors_found", LogCtxNone, "zones_total" to all.size, "readable" to readable.size, "unreadable" to unreadable,
            "not_temperature" to notTemp, "soc_readable" to zones!!.size, "soc_zones" to zones!!.joinToString(",") { it.first },
            "battery_service" to true, "thermal_status_api" to true, "api" to android.os.Build.VERSION.SDK_INT, "soc" to android.os.Build.SOC_MODEL)
    }

    private fun sample(c: Context): QaThermal.Sample {
        val b: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = c.getSystemService(BatteryManager::class.java)
        val pm = c.getSystemService(PowerManager::class.java)
        fun int(k: String) = b?.getIntExtra(k, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val plugged = when (int(BatteryManager.EXTRA_PLUGGED)) { null -> null; 0 -> "none"; 1 -> "ac"; 2 -> "usb"; 4 -> "wireless"; else -> "other" }
        val ua = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()?.takeIf { it != Long.MIN_VALUE }
        val soc = (zones ?: emptyList()).mapNotNull { (n, f) -> QaThermal.celsius(read(f))?.let { n to it } }
        return QaThermal.Sample(
            job = kind, batteryTempC = int(BatteryManager.EXTRA_TEMPERATURE)?.let { it / 10.0 }, batteryLevel = int(BatteryManager.EXTRA_LEVEL),
            voltageMv = int(BatteryManager.EXTRA_VOLTAGE), currentUa = ua, plugged = plugged,
            thermalStatus = runCatching { pm.currentThermalStatus }.getOrNull(), headroom10s = runCatching { pm.getThermalHeadroom(10) }.getOrNull(), socZones = soc,
        )
    }
}
