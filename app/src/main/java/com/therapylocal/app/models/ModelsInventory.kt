package com.therapylocal.app.models

import android.content.Context
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.io.File

/**
 * What lies in the models folder (`getExternalFilesDir(null)/models/`), sorted into what the app knows and what it does not. Only reports:
 * nothing here deletes or moves a file (AD-03: old speech models are wiped by hand). Names and sizes only, never content.
 */
object ModelsInventory {
    const val TAG = "Models"

    /** A file under the models folder: [path] is relative to it, with `/` separators. */
    data class Entry(val path: String, val size: Long)

    data class Item(val name: String, val size: Long)

    data class Result(val complete: List<Item>, val partial: List<Item>, val unknown: List<Item>) {
        val known: Int get() = complete.size + partial.size
    }

    /** Pure: sorts [files] against the files [specs] expect. An unknown top-level folder is one item whose size is the sum of its files. */
    fun classify(files: List<Entry>, specs: List<ModelSpec>): Result {
        val expected = HashMap<String, Long>() // relative path -> size
        for (s in specs) for (f in s.files) expected[(s.subdir?.let { "$it/" } ?: "") + f.name] = f.size
        val knownDirs = specs.mapNotNull { it.subdir }.toSet()
        val complete = ArrayList<Item>()
        val partial = ArrayList<Item>()
        val unknown = LinkedHashMap<String, Long>()
        for (e in files.sortedBy { it.path }) {
            val size = expected[e.path]
            val part = if (e.path.endsWith(".part")) expected[e.path.removeSuffix(".part")] else null
            when {
                size != null -> (if (e.size == size) complete else partial).add(Item(e.path, e.size))
                part != null -> partial.add(Item(e.path, e.size))
                else -> {
                    val top = e.path.substringBefore('/')
                    // Inside a known folder the file itself is unexpected; elsewhere the whole top-level entry is.
                    val key = if (e.path.contains('/') && top in knownDirs) e.path else top
                    unknown[key] = (unknown[key] ?: 0L) + e.size
                }
            }
        }
        return Result(complete, partial, unknown.map { Item(it.key, it.value) })
    }

    fun list(root: File): List<Entry> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown().filter { it.isFile }.map { Entry(it.relativeTo(root).invariantSeparatorsPath, it.length()) }.toList()

    fun scan(context: Context): Result = classify(list(ModelSpec.root(context)), ModelRegistry.ALL)

    /** The one-line text for the log: `name:bytes,name:bytes`. */
    fun render(items: List<Item>): String = items.joinToString(",") { "${it.name}:${it.size}" }

    /** Logs `models_dir_inventory` (INFO) and, when something unexpected is there, `models_dir_unexpected` (WARN). Never throws. */
    fun log(context: Context): Result? = runCatching {
        val r = scan(context)
        AppLog.i(
            TAG, "models_dir_inventory", LogCtx.NONE,
            "known" to r.known, "complete" to render(r.complete), "partial" to render(r.partial),
            "unknown" to render(r.unknown), "unknown_count" to r.unknown.size,
        )
        if (r.unknown.isNotEmpty()) {
            AppLog.w(
                TAG, "models_dir_unexpected", LogCtx.NONE,
                "count" to r.unknown.size, "unknown" to render(r.unknown), "unknown_mb" to (r.unknown.sumOf { it.size } shr 20),
                "note" to "not read by the app, not deleted (AD-03); remove by hand",
            )
        }
        r
    }.onFailure { AppLog.e(TAG, "models_dir_inventory_failed", LogCtx.NONE, it) }.getOrNull()
}
