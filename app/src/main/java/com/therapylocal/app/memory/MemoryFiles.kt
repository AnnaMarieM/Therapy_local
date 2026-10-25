package com.therapylocal.app.memory

import android.content.Context
import java.io.File

/**
 * Patient files live under [secureRoot] (internal storage, every file a SecureFile). [devRoot] (external) only holds developer
 * inputs (job.json, prompts/, pushed test transcripts) and debug-harness diagnostics.
 */
object MemoryFiles {
    fun secureRoot(c: Context): File = File(c.filesDir, "memory")

    /** [fileName] = SessionJob.file = "<sid>.md". */
    fun transcript(c: Context, fileName: String): File = File(secureRoot(c), "in/$fileName")
    fun queue(c: Context): File = File(secureRoot(c), "queue.json")

    /** A directory. */
    fun extractCache(c: Context, sessionId: String): File = File(secureRoot(c), "cache/$sessionId")
    fun devRoot(c: Context): File = File(c.getExternalFilesDir(null), "memory")
}
