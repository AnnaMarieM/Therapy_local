package com.therapylocal.app.engine

import android.content.Context

/**
 * The engines in this process: today's behaviour, the in-process fallback (`ENGINE_IN_PROCESS`), and what tests and probes run on.
 * Exceptions pass through unchanged, which is perfect fidelity. [closeAsync] runs inline, as `Embedder.close` always did.
 */
class LocalEngineHost(context: Context, override val role: EngineRole = EngineRole.LOCAL) : EngineHost {
    private val server = EngineServer(context, role, lowerPriority = false)

    override fun call(op: EngineOp, phases: PhaseSink): EngineReply = server.handle(op, phases)

    override fun closeAsync(op: EngineOp.Close) {
        server.handle(op)
    }
}
