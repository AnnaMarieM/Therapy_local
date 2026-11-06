package com.therapylocal.app.engine.stress

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The lane the run belongs to: instrumentation arguments (`-e lane stress-a -e scale 1 ...`) and the files the pull step collects. */
object LaneArgs {
    private val args get() = InstrumentationRegistry.getArguments()

    val lane: String get() = args.getString("lane") ?: "local"
    val scale: Double get() = args.getString("scale")?.toDoubleOrNull() ?: 1.0
    val backendOverride: String get() = args.getString("backendOverride") ?: "auto"
    val fakeModelMb: Int get() = args.getString("fakeModelMb")?.toIntOrNull() ?: 0
    val commit: String get() = args.getString("commit") ?: "unknown"
    val image: String get() = args.getString("image") ?: if (Build.FINGERPRINT.contains("google_apis")) "google_apis" else Build.TAGS

    /** `files/stress/` under the external files dir: `adb pull` can read it (the internal `filesDir` is not readable by the shell). */
    fun stressDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "stress").also { it.mkdirs() }
    }

    fun memTotalMb(): Long = meminfo("MemTotal") / 1024
    fun swapTotalMb(): Long = meminfo("SwapTotal") / 1024

    private fun meminfo(key: String): Long =
        runCatching { File("/proc/meminfo").readLines().first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong() }.getOrDefault(0)
}
