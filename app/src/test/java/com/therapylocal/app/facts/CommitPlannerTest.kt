package com.therapylocal.app.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPlannerTest {
    private fun vec(vararg x: Float) = x
    private fun cand(heading: String, v: FloatArray, seg: Int = 1) = Candidate(Fact(heading, "$heading, said plainly.", "quote of $heading", seg), v)

    /** Stored facts of earlier sessions with their vectors; [earlier] ranks them like the store does. */
    private val stored = listOf(Known(10, "Sister in Leeds", "Ann lives in Leeds.") to vec(1f, 0f, 0f), Known(11, "Works nights", "Works night shifts.") to vec(0f, 1f, 0f))
    private val earlier: (FloatArray) -> List<Pair<Known, Float>> = { q -> stored.map { (k, v) -> k to com.therapylocal.app.data.VectorMath.cosine(q, v) }.sortedByDescending { it.second } }

    /** A fake LLM: answers from a table keyed by candidate heading, and records what it was shown. */
    private class FakeReview(val answers: Map<String, (List<Known>) -> Verdict?>) {
        val calls = ArrayList<Pair<String, List<Known>>>()
        fun review(f: Fact, known: List<Known>): Verdict? {
            calls += f.heading to known
            val answer = answers[f.heading] ?: return Verdict(false, null)
            return answer(known)
        }
    }

    private fun plan(cands: List<Candidate>, review: FakeReview) = CommitPlanner.plan(cands, earlier, review::review, minSimilarity = 0.6f, neighbours = 10)

    @Test
    fun candidatesBelowThePrefilterAreNewWithoutAskingTheLlm() {
        val review = FakeReview(emptyMap())
        val p = plan(listOf(cand("Dog called Rex", vec(0f, 0f, 1f))), review)
        assertEquals(listOf("Dog called Rex"), p.facts.map { it.fact.heading })
        assertTrue(p.mentions.isEmpty())
        assertTrue(review.calls.isEmpty())
    }

    @Test
    fun onlyKnownFactsAtLeastAsCloseAsTheThresholdAreShown() {
        val review = FakeReview(emptyMap())
        // cos to fact 10 is 0.8, to fact 11 is 0.6 (exactly the threshold), so both are shown, nearest first.
        plan(listOf(cand("Sister moved", vec(0.8f, 0.6f, 0f))), review)
        assertEquals(listOf(10L, 11L), review.calls.single().second.map { it.factId })
        val review2 = FakeReview(emptyMap())
        plan(listOf(cand("Sister moved", vec(0.9f, 0.43f, 0f))), review2)
        assertEquals(listOf(10L), review2.calls.single().second.map { it.factId })
    }

    @Test
    fun aDuplicateOfAnEarlierFactBecomesAMentionOfIt() {
        val review = FakeReview(mapOf("Sister lives in Leeds" to { known -> Verdict(true, known.indexOfFirst { it.factId == 10L }) }))
        val c = cand("Sister lives in Leeds", vec(0.95f, 0.1f, 0f), seg = 7)
        val p = plan(listOf(c), review)
        assertTrue(p.facts.isEmpty())
        assertEquals(listOf(10L to c.fact), p.mentions)
    }

    @Test
    fun aRepeatWithinTheSessionIsDroppedNotMentioned() {
        val first = cand("Dog called Rex", vec(0f, 0f, 1f), seg = 2)
        val again = cand("Has a dog, Rex", vec(0f, 0.1f, 0.99f), seg = 9)
        val review = FakeReview(mapOf("Has a dog, Rex" to { known -> Verdict(true, known.indexOfFirst { it.factId == null }) }))
        val p = plan(listOf(first, again), review)
        // The second one was shown the fact added just before it in this session (no id) and called a duplicate of it.
        assertEquals(listOf<Long?>(null), review.calls.single().second.map { it.factId })
        assertEquals(listOf(first), p.facts)
        assertTrue(p.mentions.isEmpty())
    }

    @Test
    fun anUnreadableReplyIsAskedOnceMoreThenTheCandidateIsNew() {
        var asked = 0
        val unreadable = FakeReview(mapOf("Sister moved" to { _ -> asked++; null }))
        val p = plan(listOf(cand("Sister moved", vec(1f, 0f, 0f))), unreadable)
        assertEquals(2, asked)
        assertEquals(1, p.facts.size)

        // Unreadable once, readable on the second ask: the second answer counts.
        var n = 0
        val flaky = FakeReview(mapOf("Sister moved" to { _ -> if (n++ == 0) null else Verdict(true, 0) }))
        val q = plan(listOf(cand("Sister moved", vec(1f, 0f, 0f))), flaky)
        assertEquals(listOf(10L), q.mentions.map { it.first })
        assertTrue(q.facts.isEmpty())
    }

    @Test
    fun aDuplicateVerdictPointingNowhereKeepsTheCandidate() {
        val review = FakeReview(mapOf("Sister moved" to { _ -> Verdict(true, 5) }))
        val p = plan(listOf(cand("Sister moved", vec(1f, 0f, 0f))), review)
        assertEquals(1, p.facts.size)
        assertTrue(p.mentions.isEmpty())
    }

    @Test
    fun theSameInputsGiveTheSamePlan() {
        fun run(): CommitPlan {
            val review = FakeReview(mapOf("Sister lives in Leeds" to { _ -> Verdict(true, 0) }, "Has a dog, Rex" to { k -> Verdict(true, k.indexOfFirst { it.factId == null }) }))
            val cands = listOf(cand("Dog called Rex", vec(0f, 0f, 1f)), cand("Sister lives in Leeds", vec(0.95f, 0.1f, 0f)), cand("Has a dog, Rex", vec(0f, 0.1f, 0.99f)), cand("Night shifts", vec(0.1f, 0.99f, 0f)))
            return CommitPlanner.plan(cands, earlier, review::review, 0.6f, 10)
        }
        val a = run()
        val b = run()
        assertEquals(a.facts.map { it.fact }, b.facts.map { it.fact })
        assertEquals(a.mentions, b.mentions)
        assertEquals(listOf("Dog called Rex", "Night shifts"), a.facts.map { it.fact.heading })
        assertEquals(listOf(10L), a.mentions.map { it.first })
    }

    @Test
    fun stepIsCalledOncePerCandidateInOrder() {
        val steps = ArrayList<Int>()
        CommitPlanner.plan(listOf(cand("a", vec(0f, 0f, 1f)), cand("b", vec(0f, 0f, -1f))), earlier, { _, _ -> null }, 0.6f, 10) { steps += it }
        assertEquals(listOf(0, 1), steps)
    }
}
