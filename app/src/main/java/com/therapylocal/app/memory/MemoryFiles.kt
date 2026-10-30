package com.therapylocal.app.memory

import android.content.Context
import java.io.File

/**
 * Patient files live under [secureRoot] (internal storage, every file a SecureFile): today only the audio of recordings until they are
 * processed. [devRoot] (external) only holds developer inputs (job.json, prompts/, pushed test transcripts in in/) and debug-harness markers in out/.
 */
object MemoryFiles {
    fun secureRoot(c: Context): File = File(c.filesDir, "memory")
    fun devRoot(c: Context): File = File(c.getExternalFilesDir(null), "memory")

    /** A developer copy in the dev root's prompts/ wins (edit without a rebuild); otherwise the prompt shipped in the app (demo/prompts). */
    fun prompt(c: Context, name: String): String =
        File(devRoot(c), "prompts/$name").takeIf { it.exists() }?.readText()?.trim() ?: c.assets.open("prompts/$name").bufferedReader().use { it.readText() }.trim()
}
