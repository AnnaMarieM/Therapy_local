package com.therapylocal.app.engine

import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/** The fault seam in debug. Same as the no-op copy for now; the fault plan itself is added with the lab (S11). */
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
