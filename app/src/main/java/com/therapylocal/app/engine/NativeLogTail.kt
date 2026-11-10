package com.therapylocal.app.engine

import java.util.concurrent.TimeUnit

/**
 * The last native warnings and errors of a host process that died, for `engine_died` (a quiet native crash leaves no tombstone, AD-04, and the
 * host's own log ends at the last phase). The app may read the log lines of its own uid, and `--pid` narrows them to the dead host. Only the
 * tags of the native runtimes (LiteRT, QNN, the allocator, the C library) and only warnings and above are kept, a few lines, cut short: they
 * name a failed allocation or a rejected context binary, never text the user wrote.
 */
internal object NativeLogTail {
    private val TAGS = Regex("""litert|native|scudo|libc\b|qnn|htp|dsp|adsprpc|linker|DEBUG|tflite""", RegexOption.IGNORE_CASE)
    private const val MAX_LINES = 6
    private const val MAX_CHARS = 220

    fun of(pid: Int): String? = runCatching {
        if (pid <= 0) return null
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "800", "--pid=$pid").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        filter(lines)
    }.getOrNull()

    /** Warnings and above from native runtime tags, the last few, each cut to [MAX_CHARS]; null when there are none. */
    internal fun filter(lines: List<String>): String? = lines.asSequence()
        // threadtime: "11-07 01:09:41.380  4511  5921 I scudo   : message" (priority is the 5th field)
        .filter { l -> val f = l.split(Regex("\\s+"), 7); f.size >= 7 && f[4] in setOf("W", "E", "F") && TAGS.containsMatchIn(f[5]) || l.contains("Scudo ERROR") }
        .map { it.substringAfter(": ", it).take(MAX_CHARS) }
        .toList().takeLast(MAX_LINES).takeIf { it.isNotEmpty() }?.joinToString(" | ")
}
