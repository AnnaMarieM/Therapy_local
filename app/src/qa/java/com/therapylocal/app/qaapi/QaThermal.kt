package com.therapylocal.app.qaapi

import java.util.Locale

/**
 * Pure parts of the in-app thermal sampler (QA build only, see [QaThermalSampler]): which sysfs thermal zones are SoC sensors, how a raw zone
 * value becomes degrees C, the `key=value` fields of one sample, and the running stats for the summary line. No Android types, so JVM tests cover it.
 */
object QaThermal {
    const val SETTING_KEY = "thermal_log"
    const val TAG = "Thermal"

    /** Same classification as qa/thermal/thermal_log.py: CPU clusters, GPU, NPU/DSP (nsp), aoss, modem, ddr, camera/video, PMICs, skin, quiet, xo. */
    private val SOC = Regex("^(cpu|cpuss|gpu|gpuss|npu|nsp|aoss|mdm|mdmss|ddr|video|camera|pm\\d|pm[a-z]|soc|xo|quiet|skin)", RegexOption.IGNORE_CASE)
    /** Battery current limiting levels, voltage and current readings that share /sys/class/thermal with the temperatures. */
    private val NOT_TEMP = Regex("(^|[-_])(bcl|ibat|vbat|socd)([-_]|$)|-lvl\\d$", RegexOption.IGNORE_CASE)

    fun isTemperatureZone(type: String): Boolean = !NOT_TEMP.containsMatchIn(type)
    fun isSoc(type: String): Boolean = isTemperatureZone(type) && SOC.containsMatchIn(type)

    /** A sysfs value (milli-degrees, or degrees on some kernels) as C; null for text that is not a number and for placeholders such as -273000. */
    fun celsius(raw: String?): Double? {
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        val c = if (Math.abs(v) >= 1000) v / 1000.0 else v
        return c.takeIf { it > -60 && it < 200 }
    }

    fun f1(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.1f", it) }

    class Sample(
        val job: String?, val batteryTempC: Double?, val batteryLevel: Int?, val voltageMv: Int?, val currentUa: Long?, val plugged: String?,
        val thermalStatus: Int?, val headroom10s: Float?, val socZones: List<Pair<String, Double>>,
    )

    /** The fields of the per-second debug line `evt=thermal_sample`. Numbers and zone names only. */
    fun fields(s: Sample): List<Pair<String, Any?>> = listOf(
        "job" to (s.job ?: "none"), "bat_c" to f1(s.batteryTempC), "level" to s.batteryLevel, "mv" to s.voltageMv, "ua" to s.currentUa, "plugged" to s.plugged,
        "thermal_status" to s.thermalStatus, "headroom_10s" to s.headroom10s?.takeIf { !it.isNaN() }?.let { String.format(Locale.ROOT, "%.2f", it) },
        "soc_max_c" to f1(s.socZones.maxOfOrNull { it.second }),
        "soc" to s.socZones.takeIf { it.isNotEmpty() }?.joinToString(",") { "${it.first}:${f1(it.second)}" },
    ).filter { it.second != null }

    /** Count, mean and max of one value over a run of samples. */
    class Stats {
        var n = 0; private set
        var max = Double.NaN; private set
        private var sum = 0.0
        fun add(v: Double?) { if (v == null) return; n++; sum += v; if (max.isNaN() || v > max) max = v }
        val mean: Double get() = if (n == 0) Double.NaN else sum / n
        fun summary(): String? = if (n == 0) null else "${f1(max)}/${f1(mean)}"
    }

    /** The `thermal_summary` fields when the queue goes quiet: `max/mean` of battery and the hottest SoC zone, seconds, and thermal status maximum. */
    class RunSummary {
        val battery = Stats(); val soc = Stats(); var statusMax = 0; var seconds = 0
        fun add(s: Sample) { seconds++; battery.add(s.batteryTempC); soc.add(s.socZones.maxOfOrNull { it.second }); s.thermalStatus?.let { if (it > statusMax) statusMax = it } }
        fun fields(): List<Pair<String, Any?>> = listOf("seconds" to seconds, "bat_c_max_mean" to battery.summary(), "soc_c_max_mean" to soc.summary(), "thermal_status_max" to statusMax)
            .filter { it.second != null }
    }

    /** The `thermal_log` entry of the settings registry. [read] and [apply] go to the preference; true is the default in the QA build. */
    fun setting(read: () -> Boolean, apply: (Boolean) -> Unit) = QaSetting(
        SETTING_KEY, QaSettingType.BOOL, true,
        "Debug-level thermal log (tag TL, evt=thermal_sample) once a second while the job queue runs: battery, thermal status, headroom, readable SoC zones.",
        read = read, apply = { apply(it as Boolean) },
    )
}
