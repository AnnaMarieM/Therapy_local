package com.therapylocal.app.memory

import com.therapylocal.app.data.VectorMath

/** A fact the candidate may repeat: one stored in an earlier session ([factId]), or one this session adds before it ([factId] null). */
data class Known(val factId: Long?, val heading: String, val content: String)

/** An extracted fact with the embedding of its heading and content. */
class Candidate(val fact: Fact, val vec: FloatArray)

/** What a session's commit writes: its new facts, in order, and its mentions of earlier sessions' facts. */
data class CommitPlan(val facts: List<Candidate>, val mentions: List<Pair<Long, Fact>>)

/**
 * Decides, one candidate at a time and in order, whether it is a new fact, a mention of a fact from an earlier session, or a repeat of a
 * fact this session already adds (dropped: it is the same fact said twice in one session). Pure: the store and the LLM come in as functions.
 *
 * Only facts at least [minSimilarity] close to the candidate are shown to [review]; with none, the candidate is new without asking. An
 * unreadable review is asked once more, then the candidate counts as new, so a model failure never loses a fact.
 */
object CommitPlanner {
    fun plan(
        candidates: List<Candidate>,
        /** The nearest facts of earlier sessions, best first. */
        earlier: (FloatArray) -> List<Pair<Known, Float>>,
        /** The LLM's verdict on [Fact] against the listed known facts, or null if its reply was unreadable. */
        review: (Fact, List<Known>) -> Verdict?,
        minSimilarity: Float,
        neighbours: Int,
        /** Called before each candidate, with its 0-based index. */
        step: (Int) -> Unit = {},
    ): CommitPlan {
        val added = ArrayList<Candidate>()
        val mentions = ArrayList<Pair<Long, Fact>>()
        candidates.forEachIndexed { i, c ->
            step(i)
            val ownSoFar = added.map { a -> Known(null, a.fact.heading, a.fact.content) to VectorMath.cosine(a.vec, c.vec) }
            val near = (earlier(c.vec) + ownSoFar).filter { it.second >= minSimilarity }.sortedByDescending { it.second }.take(neighbours).map { it.first }
            val verdict = if (near.isEmpty()) null else review(c.fact, near) ?: review(c.fact, near)
            val of = verdict?.takeIf { it.duplicate }?.of?.let(near::getOrNull)
            when {
                of == null -> added += c
                of.factId != null -> mentions += of.factId to c.fact
                // else: a repeat within this session, dropped
            }
        }
        return CommitPlan(added, mentions)
    }
}
