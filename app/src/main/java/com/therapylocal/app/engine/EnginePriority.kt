package com.therapylocal.app.engine

import android.os.Process

/** The thread-priority policy of the engine host processes (AD-18). Pure, so a JVM test pins it. */
internal object EnginePriority {
    /**
     * The priority a host process gives the binder thread before [op], or null to leave it alone.
     * BATCH: every op at [Process.THREAD_PRIORITY_DEFAULT] (nice 0), set explicitly so a reused binder thread that was lowered earlier is raised.
     * LIVE: [Process.THREAD_PRIORITY_BACKGROUND] (nice 10) for Open and the embedder ops, since it runs beside the recorder and the UI (the rest there are cheap).
     * LOCAL: none, the thread belongs to the main process.
     */
    fun of(role: EngineRole, op: EngineOp): Int? = when (role) {
        EngineRole.BATCH -> Process.THREAD_PRIORITY_DEFAULT
        EngineRole.LIVE ->
            if (op is EngineOp.Open || op is EngineOp.EmbedText || op is EngineOp.EmbedAudio) Process.THREAD_PRIORITY_BACKGROUND else null
        EngineRole.LOCAL -> null
    }
}
