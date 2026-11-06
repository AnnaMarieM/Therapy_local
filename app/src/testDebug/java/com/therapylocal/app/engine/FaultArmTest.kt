package com.therapylocal.app.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** N41 (debug half): `ArmFaults` installs a plan, `startNo` counts per role across generations, rules fire as specified. */
class FaultArmTest {
    private val tag = CallTag("r", null, "ask")
    private val ask = EngineOp.Ask(tag, 1, "s", "u", null, 10, 0.1, 10)

    private fun arm(rules: String, startNo: Int = 1) =
        Faults.arm(EngineOp.ArmFaults(CallTag("a", null, "arm_faults"), """{"v":1,"rules":[$rules]}""", startNo))

    @Before @After fun clean() {
        Faults.disarm()
        Faults.resetStarts()
        Faults.plan = null
    }

    private fun throwsOn(point: String, role: EngineRole = EngineRole.BATCH, op: EngineOp? = ask): Boolean = try {
        Faults.at(point, role, op)
        false
    } catch (e: IllegalStateException) {
        true
    }

    @Test fun armAnswersDoneAndRejectsBadPlans() {
        assertEquals(EngineReply.Done, arm(""))
        try {
            arm("""{"at":"ask","do":{"kind":"explode"}}""")
            fail()
        } catch (_: FaultPlanException) {
        }
    }

    @Test fun startNumbersCountPerRoleAcrossGenerations() {
        assertEquals(1, Faults.nextStart(EngineRole.BATCH))
        assertEquals(2, Faults.nextStart(EngineRole.BATCH))
        assertEquals(1, Faults.nextStart(EngineRole.LIVE))
        assertEquals(3, Faults.nextStart(EngineRole.BATCH))
        Faults.resetStarts()
        assertEquals(1, Faults.nextStart(EngineRole.BATCH))
    }

    @Test fun ruleFiresOnItsStartRoleAndPoint() {
        val rule = """{"role":"batch","at":"ask","when":{"start":[2]},"do":{"kind":"throw","type":"IllegalStateException","message":"boom"}}"""
        arm(rule, startNo = 1)
        assertTrue(!throwsOn("before:ask"))
        Faults.disarm()
        arm(rule, startNo = 2)
        assertTrue(!throwsOn("before:ask", EngineRole.LIVE))
        assertTrue(throwsOn("before:ask"))
    }

    @Test fun failNTimesThenSucceed() {
        arm("""{"at":"ask","when":{"times":2},"do":{"kind":"throw","type":"IllegalStateException"}}""")
        assertTrue(throwsOn("before:ask"))
        assertTrue(throwsOn("before:ask"))
        assertTrue(!throwsOn("before:ask"))
    }

    @Test fun nthAndCallNumber() {
        arm("""{"at":"ask","when":{"nth":2,"times":1},"do":{"kind":"throw","type":"IllegalStateException"}}""")
        assertTrue(!throwsOn("before:ask"))
        assertTrue(throwsOn("before:ask"))
        assertTrue(!throwsOn("before:ask"))
        Faults.disarm()
        arm("""{"at":"ask","when":{"call":2,"times":-1},"do":{"kind":"throw","type":"IllegalStateException"}}""")
        assertTrue(!throwsOn("before:ask"))
        assertTrue(throwsOn("before:ask"))
    }

    @Test fun statusAndArmDoNotCountAsCalls() {
        arm("""{"at":"ask","when":{"call":1},"do":{"kind":"throw","type":"IllegalStateException"}}""")
        Faults.at("before:status", EngineRole.BATCH, EngineOp.Status(CallTag("s", null, "status")))
        assertTrue(throwsOn("before:ask"))
    }

    @Test fun phaseRulesNeedTheModel() {
        arm("""{"at":"initialize","model":"fake-llm","do":{"kind":"throw","type":"IllegalStateException"}}""")
        val open = EngineOp.Open(CallTag("o", null, "open"), EngineKind.LLM, "fake-embed", "fake-embed", "GPU")
        Faults.at("phase:initialize", EngineRole.BATCH, open, "fake-embed", "GPU")
        try {
            Faults.at("phase:initialize", EngineRole.BATCH, open, "fake-llm", "GPU")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun replyRulesCorruptVectors() {
        arm("""{"at":"embed_text","do":{"kind":"reply","corrupt":"wrong_dim"}}""")
        val op = EngineOp.EmbedText(CallTag("e", null, "embed_text"), 1, "t", false)
        val out = Faults.reply(EngineRole.LIVE, op, EngineReply.Vector(FloatArray(768))) as EngineReply.Vector
        assertEquals(767, out.v.size)
        val again = Faults.reply(EngineRole.LIVE, op, EngineReply.Vector(FloatArray(768))) as EngineReply.Vector
        assertEquals(768, again.v.size) // times defaults to 1
    }

    @Test fun fakesAreDeterministic() {
        val a = FakeRunners.vectorOfText("my boss is unhappy", 768)
        assertEquals(a.toList(), FakeRunners.vectorOfText("my boss is unhappy", 768).toList())
        assertEquals(1.0, a.fold(0.0) { s, x -> s + x * x }, 1e-4)
        val shared = FakeRunners.vectorOfText("my boss is unhappy", 768).zip(FakeRunners.vectorOfText("my boss is happy", 768)).sumOf { (x, y) -> (x * y).toDouble() }
        val other = FakeRunners.vectorOfText("we went hiking", 768).zip(a).sumOf { (x, y) -> (x * y).toDouble() }
        assertTrue(shared > other)
        assertEquals(FakeRunners.crcOfPcm(shortArrayOf(1, 2, 3)), FakeRunners.crcOfPcm(shortArrayOf(1, 2, 3)))
    }
}
