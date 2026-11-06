package com.therapylocal.app.engine

import com.therapylocal.app.config.EngineTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** N41 (non-debug half): the noFaults seam refuses a plan, never fires, never replaces a reply, and LabWiring is the identity. */
class NoFaultsTest {
    @Test fun armIsRefused() {
        assertEquals(EngineReply.Refused, Faults.arm(EngineOp.ArmFaults(CallTag("a", null, "arm_faults"), "{}", 1)))
        assertNull(Faults.plan)
    }

    @Test fun hooksAreInert() {
        Faults.at("before:ask", EngineRole.BATCH, null)
        val r = EngineReply.Done
        assertSame(r, Faults.reply(EngineRole.BATCH, EngineOp.Status(CallTag("s", null, "status")), r))
    }

    @Test fun labWiringIsTheIdentity() {
        val t = EngineTuning()
        assertSame(t, LabWiring.tuning(t))
        assertEquals("gemma-4-e2b", LabWiring.model("gemma-4-e2b"))
        assertEquals(false, LabWiring.ready(false))
        assertEquals("x", LabWiring.gate("x"))
    }
}
