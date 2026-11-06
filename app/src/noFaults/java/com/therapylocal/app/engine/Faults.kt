package com.therapylocal.app.engine

import android.content.Context
import com.therapylocal.app.engine.runner.Runner
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * The fault seam in release, feel and qa: every hook is a no-op and a plan is refused. The real one lives in src/debug. Same members as
 * that copy, because main code calls them directly (never by reflection). Nothing here names a fault, a fake or a lab class.
 */
object Faults {
    @Suppress("UNUSED_PARAMETER")
    fun at(point: String, role: EngineRole, op: EngineOp?, model: String? = null, backend: String? = null) {
    }

    fun arm(op: EngineOp.ArmFaults): EngineReply {
        AppLog.w("Engine", EngineEvents.FAULT_PLAN_IGNORED, LogCtx.NONE, "req" to op.tag.requestId, "op" to op.tag.op, "start_no" to op.startNo)
        return EngineReply.Refused
    }

    /** Debug: a fake engine for a `fake-*` model id. Never here. */
    @Suppress("UNUSED_PARAMETER")
    internal fun runner(op: EngineOp.Open, role: EngineRole, phases: PhaseSink): Pair<Runner, EngineReply.Opened>? = null

    /** Debug: may replace a reply. Here it is the reply. */
    @Suppress("UNUSED_PARAMETER")
    fun reply(role: EngineRole, op: EngineOp, reply: EngineReply): EngineReply = reply

    /** Host process start (`App.onCreate`): the debug copy reads the pre-attach plan file. */
    @Suppress("UNUSED_PARAMETER")
    fun hostStart(context: Context, role: EngineRole) {
    }

    /** Main, before each bind: the debug copy writes the pre-attach plan file and returns the start number. Here 0. */
    @Suppress("UNUSED_PARAMETER")
    fun beforeBind(context: Context, role: EngineRole): Int = 0

    /** Main side plan that is sent right after each attach. Always null here, so `ArmFaults` is never sent. */
    val plan: String? = null
}
