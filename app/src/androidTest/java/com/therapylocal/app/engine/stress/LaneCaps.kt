package com.therapylocal.app.engine.stress

import org.json.JSONObject
import java.io.File

/**
 * What the probes learned about this lane (`files/stress/lane_caps.json`). Other tests read these facts instead of assuming them
 * (anti-flake rule 5): `Assume.assumeTrue(LaneCaps.bool("lmk_reports"))`. The probe tests write them; tests that need a fact before the
 * probe class ran (the runner does not order classes) call [ensure], which runs the cheap probes once.
 */
object LaneCaps {
    private val file get() = File(LaneArgs.stressDir(), "lane_caps.json")

    @Synchronized
    fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    @Synchronized
    fun put(key: String, value: Any?) {
        val j = read()
        j.put(key, value ?: JSONObject.NULL)
        file.writeText(j.toString(2))
    }

    fun has(key: String) = read().has(key)
    fun bool(key: String, default: Boolean = false) = read().optBoolean(key, default)
    fun int(key: String, default: Int = -1) = read().optInt(key, default)
    fun string(key: String, default: String = "") = read().optString(key, default)

    /** The lane-level facts that need no host: memory, swap, the lmkd report support. Idempotent. */
    fun ensure() {
        if (has("zram_swap_mb")) return
        put("lane", LaneArgs.lane)
        put("api", android.os.Build.VERSION.SDK_INT)
        put("device", android.os.Build.MODEL)
        put("ram_mb", LaneArgs.memTotalMb())
        // Swap delays lmkd (finding 30): the job summary shows it next to the LMK results.
        put("zram_swap_mb", LaneArgs.swapTotalMb())
        put("lmk_reports", runCatching {
            android.app.ActivityManager.isLowMemoryKillReportSupported()
        }.getOrDefault(false))
    }
}
