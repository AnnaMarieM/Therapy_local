package com.therapylocal.app.engine

import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/** The fault seam in release, feel and qa: every hook is a no-op and a plan is refused. The real one lives in src/debug. */
object Faults {
    @Suppress("UNUSED_PARAMETER")
    fun at(point: String, role: EngineRole, op: EngineOp?) {
    }

    @Suppress("UNUSED_PARAMETER")
    fun arm(op: EngineOp.ArmFaults): EngineReply {
        AppLog.w("Engine", EngineEvents.FAULT_PLAN_IGNORED, LogCtx.NONE, "req" to op.tag.requestId, "op" to op.tag.op, "start_no" to op.startNo)
        return EngineReply.Refused
    }

    val plan: String? = null
}
