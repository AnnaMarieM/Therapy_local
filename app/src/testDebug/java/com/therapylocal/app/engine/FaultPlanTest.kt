package com.therapylocal.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** N22: the fault-plan parser (catalogue 2.3). Debug only: FaultPlan exists in no other build type. */
class FaultPlanTest {
    private fun plan(rules: String, extra: String = "") =
        """{"v":1,"id":"t","scale":2.0,"gate":"allow_all","fake":{"llm":{"ctx":2048,"reply":"echo","perf":{"prefill_tps":1.0,"decode_tps":2.0}},"embed":{"dim":768},"asr":{"mode":"crc"},"resident_mb":0}$extra,"rules":[$rules]}"""

    private fun rejected(json: String): String {
        try {
            FaultPlan.parse(json)
        } catch (e: FaultPlanException) {
            return e.message.orEmpty()
        }
        fail("accepted: $json")
        return ""
    }

    @Test fun markerConstant() = assertEquals("FAULT_PLAN_V1", FaultPlan.FAULT_PLAN_V1)

    @Test fun validPlanParses() {
        val p = FaultPlan.parse(
            plan("""{"role":"batch","model":"fake-llm","backend":"GPU","at":"initialize","when":{"start":[1],"call":null,"nth":1,"times":1},"do":{"kind":"signal","sig":"ABRT","thread":"binder","delay_ms":0}}"""),
        )
        assertEquals(2.0, p.scale, 0.0)
        assertEquals("allow_all", p.gate)
        assertEquals(2048, p.fake.llmCtx)
        val r = p.rules.single()
        assertEquals(listOf(1), r.on.start)
        assertEquals(null, r.on.call)
        assertTrue(r.action is FaultPlan.Action.Signal)
    }

    @Test fun everyActionParses() {
        val kinds = listOf(
            """{"kind":"signal","sig":"SEGV","thread":"worker"}""", """{"kind":"exit","code":3}""",
            """{"kind":"throw","type":"HostOnlyException","message":"m","causes":["IOException"]}""",
            """{"kind":"uncaught","type":"RuntimeException","thread":"looper"}""", """{"kind":"java_oom","thread":"worker","catch":false}""",
            """{"kind":"alloc","mb":64,"step_mb":8,"step_ms":10,"alloc_kind":"shm","touch":true,"until":"killed","hold":true}""",
            """{"kind":"leak","mb_per_call":4}""", """{"kind":"hang","ms":-1,"where":"binder"}""", """{"kind":"spin","ms":100}""", """{"kind":"delay","ms":5}""",
            """{"kind":"write_input"}""",
        )
        for (k in kinds) assertEquals(1, FaultPlan.parse(plan("""{"at":"ask","do":$k}""")).rules.size)
        assertEquals(2, FaultPlan.parse(plan("""{"at":"ask","do":{"kind":"reply","bytes":10}},{"at":"embed_text","do":{"kind":"reply","corrupt":"nan"}}""")).rules.size)
        assertEquals(1, FaultPlan.parse(plan("""{"at":"ask","do":{"kind":"perf","decode_tps":1.0}}""")).rules.size)
    }

    @Test fun unknownKeysAreRejected() {
        assertTrue(rejected(plan("", ""","zzz":1""")).contains("zzz"))
        assertTrue(rejected(plan("""{"at":"ask","extra":1,"do":{"kind":"delay","ms":1}}""")).contains("extra"))
        assertTrue(rejected(plan("""{"at":"ask","do":{"kind":"delay","ms":1,"bogus":2}}""")).contains("bogus"))
        assertTrue(rejected(plan("""{"at":"ask","when":{"nope":1},"do":{"kind":"delay","ms":1}}""")).contains("nope"))
    }

    @Test fun unknownActionAndPointAreRejected() {
        assertTrue(rejected(plan("""{"at":"ask","do":{"kind":"explode"}}""")).contains("explode"))
        assertTrue(rejected(plan("""{"at":"cancel","do":{"kind":"delay","ms":1}}""")).contains("cancel")) // there is no cancel point
        assertTrue(rejected(plan("""{"at":"ask","do":{"kind":"throw","type":"java.lang.Thread"}}""")).contains("allow-list"))
        assertTrue(rejected(plan("""{"at":"ask","do":{"kind":"signal","sig":"TERM"}}""")).contains("TERM"))
    }

    @Test fun sizeCapAndVersion() {
        assertTrue(rejected(plan("""{"at":"ask","do":{"kind":"throw","type":"RuntimeException","message":"${"x".repeat(17_000)}"}}""")).contains("over"))
        rejected("""{"v":2,"rules":[]}""")
        rejected("not json")
    }

    @Test fun replyNeedsACallPoint() {
        rejected(plan("""{"at":"create","do":{"kind":"reply","bytes":1}}"""))
        rejected(plan("""{"at":"ask","do":{"kind":"reply"}}"""))
        rejected(plan("""{"at":"host_start","when":{"call":1},"do":{"kind":"delay","ms":1}}"""))
    }
}
