package com.therapylocal.app.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * One process's rotating log file `<base>.log` (older ones `<base>.1.log` ...). Not thread-safe: [AppLog] calls it from its one writer thread.
 * It re-opens when the file was deleted (checked at most once per [recheckMs]), so a host never keeps writing into an unlinked inode.
 */
internal class RotatingLog(
    private val dir: File,
    val base: String,
    private val maxBytes: Long,
    private val keep: Int,
    private val recheckMs: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var writer: BufferedWriter? = null
    private var checkedAt = Long.MIN_VALUE

    val file: File get() = File(dir, "$base.log")

    fun open() {
        close()
        dir.mkdirs()
        writer = BufferedWriter(FileWriter(file, true))
        checkedAt = now()
    }

    fun write(line: String) {
        ensureOpen()
        val w = writer ?: return
        w.write(line)
        w.write("\n")
        w.flush()
        if (file.length() > maxBytes) rotate()
    }

    fun flush() {
        writer?.flush()
    }

    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    /** Opens the writer if there is none, or if the file is gone (looked at once per [recheckMs]). */
    private fun ensureOpen() {
        val t = now()
        if (writer == null) return open()
        if (t - checkedAt >= recheckMs) {
            checkedAt = t
            if (!file.exists()) open()
        }
    }

    private fun rotate() {
        close()
        File(dir, "$base.${keep - 1}.log").delete()
        for (i in keep - 2 downTo 1) File(dir, "$base.$i.log").renameTo(File(dir, "$base.${i + 1}.log"))
        file.renameTo(File(dir, "$base.1.log"))
        open()
    }

    /** Deletes this base's files (current and rotated) and re-opens an empty current file. */
    fun clear() {
        close()
        deleteBase(dir, base)
        open()
    }

    companion object {
        fun deleteBase(dir: File, base: String) {
            dir.listFiles()?.forEach { if (it.name == "$base.log" || Regex("${Regex.escape(base)}\\.\\d+\\.log").matches(it.name)) it.delete() }
        }
    }
}

internal object LogMerge {
    private val TS = Regex("^ts=(\\S+)")

    /**
     * The last [n] lines of the current file of each base in [bases], merged by `ts=` (stable: equal stamps keep the order of [bases], then
     * file order). A line without a stamp (a stack trace after a crash head) stays behind the line before it.
     */
    fun tailAll(dir: File, bases: List<String>, n: Int): String {
        class L(val ts: String, val text: String)
        val all = ArrayList<L>()
        for (b in bases) {
            val f = File(dir, "$b.log")
            if (!f.exists()) continue
            val lines = ArrayDeque<String>()
            runCatching {
                f.forEachLine { l ->
                    lines.addLast(l)
                    if (lines.size > n) lines.removeFirst()
                }
            }
            var ts = ""
            for (l in lines) {
                TS.find(l)?.let { ts = it.groupValues[1] }
                all += L(ts, l)
            }
        }
        val sorted = all.sortedBy { it.ts } // sortedBy is stable
        return sorted.takeLast(n).joinToString("\n") { it.text }
    }
}
