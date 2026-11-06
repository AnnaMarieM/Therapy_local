package com.therapylocal.app.engine

import android.app.Application

/** Which process this is. Main has no role; the two engine host processes are `<package>:live` and `<package>:batch` (AD-01). */
object EngineProcess {
    /** The role of a process name, or null for main (or anything else). Pure, so it is testable without Android. */
    fun roleOf(processName: String?): EngineRole? = when {
        processName == null -> null
        processName.endsWith(":live") -> EngineRole.LIVE
        processName.endsWith(":batch") -> EngineRole.BATCH
        else -> null
    }

    /** The label on every log line: `main`, `live` or `batch`. */
    fun labelOf(processName: String?): String = roleOf(processName)?.proc ?: "main"

    val processName: String? by lazy { runCatching { Application.getProcessName() }.getOrNull() }

    /** Null in the main process. */
    fun current(): EngineRole? = roleOf(processName)

    val isMain: Boolean get() = current() == null
}
