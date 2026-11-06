package com.therapylocal.app.models

import android.content.Context
import android.content.SharedPreferences
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.io.File

/**
 * One-time start-up migration to Canary as the only speech recogniser (docs/AD/AD-02.md): deletes the old speech-engine settings and the
 * downloaded Whisper and Parakeet files. Keyed by what is still there, so a run killed half-way finishes on the next start, and a later run
 * does nothing. It never throws: a file it cannot delete is logged and left in place.
 */
object SpeechMigration {
    private const val TAG = ModelSetup.TAG

    /** The settings that chose the speech engine. Also refused when a dev job.json still sets them ([com.therapylocal.app.memory.DevHarness]). */
    val LEGACY_PREFS = listOf("engine", "whisper_size")

    /** The download folders (under the models root) of the recognisers that were removed. The only place their names remain. */
    private val LEGACY_SPEECH_DIRS = listOf(
        "whisper-tiny", "whisper-base", "whisper-small", "whisper-tiny.en", "whisper-base.en", "whisper-small.en",
        "whisper-medium", "whisper-turbo", "whisper-large-v3", "parakeet-tdt-0.6b-v3",
    )

    /** The settings it reads and removes, so the JVM tests run without Android. */
    interface Prefs {
        val all: Map<String, Any?>

        /** Removes [keys] durably; false if the store could not be written. */
        fun remove(keys: Collection<String>): Boolean
    }

    fun interface Log {
        fun line(warn: Boolean, evt: String, vararg f: Pair<String, Any?>)
    }

    fun run(context: Context) {
        val settings = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        run(prefs(settings), runCatching { ModelSpec.root(context) }.getOrNull())
    }

    fun run(prefs: Prefs, modelsRoot: File?, log: Log = Log { warn, evt, f -> if (warn) AppLog.w(TAG, evt, LogCtx.NONE, *f) else AppLog.i(TAG, evt, LogCtx.NONE, *f) }) {
        runCatching { migratePrefs(prefs, log) }.onFailure { failed(log, "prefs", it) }
        if (modelsRoot != null) LEGACY_SPEECH_DIRS.forEach { id -> runCatching { removeFiles(File(modelsRoot, id), id, log) }.onFailure { failed(log, id, it) } }
    }

    private fun migratePrefs(prefs: Prefs, log: Log) {
        val all = prefs.all
        val present = LEGACY_PREFS.filter { it in all }
        if (present.isEmpty()) return
        if (!prefs.remove(present)) return log.line(true, "speech_migration_failed", "step" to "prefs", "reason" to "not_saved")
        log.line(false, "speech_engine_migrated", "from" to all["engine"]?.toString(), "whisper_size" to all["whisper_size"]?.toString())
    }

    /** [dir] is normally a folder; anything at that path (a stray file too) is ours and goes. */
    private fun removeFiles(dir: File, id: String, log: Log) {
        if (!dir.exists()) return
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (dir.deleteRecursively()) log.line(false, "model_files_removed", "id" to id, "bytes" to bytes)
        else log.line(true, "model_files_remove_failed", "id" to id, "reason" to "not_deleted", "bytes_left" to dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    private fun failed(log: Log, step: String, t: Throwable) {
        runCatching { log.line(true, "speech_migration_failed", "step" to step, "reason" to t.javaClass.simpleName) }
    }

    private fun prefs(p: SharedPreferences) = object : Prefs {
        override val all: Map<String, Any?> get() = p.all
        override fun remove(keys: Collection<String>) = p.edit().apply { keys.forEach { remove(it) } }.commit()
    }
}
