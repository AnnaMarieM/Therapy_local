package com.therapylocal.app.jobs

import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.facts.RoleGuess
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * The decision of the ROLES job without its storage: whether a guess is needed, the LLM asked (twice at most) and the answer checked
 * ([RoleGuess]). Pure apart from `ask`.
 */
object RoleStep {
    const val LLM = "llm"
    const val FALLBACK = "fallback"
    const val MAX_ATTEMPTS = 2

    /** [guess] has every speaker; [source] is what `speaker_maps.source` gets; [speakers] are in order of first appearance. */
    class Decision(val guess: RoleGuess.Guess, val source: String, val attempts: Int, val speakers: List<String>)

    /**
     * Null when nothing is to be done: a map exists already, or fewer than two distinct speakers spoke. Otherwise [ask] gets the transcript text
     * and returns the model's reply; a reply that fails the check, or a call that throws, uses one of the [MAX_ATTEMPTS], then the first speaker
     * is the therapist and the second the patient with confidence 0. A dead engine or a request to stop is not an attempt: it propagates.
     */
    fun decide(segments: List<SegmentRow>, hasMap: Boolean, ask: (input: String) -> String): Decision? {
        if (hasMap) return null
        val speakers = segments.mapNotNull { it.speaker }.distinct()
        if (speakers.size < 2) return null
        val input = RoleGuess.input(segments, MemoryConfig.ROLES_INPUT_TOKENS)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val reply = try {
                ask(input)
            } catch (t: Throwable) {
                if (t is EngineDied || t is EngineUnavailable || t is JobStopped || t is ModelSwitched || (t is Error && t !is OutOfMemoryError)) throw t
                AppLog.w(JobRunner.TAG, "roles_attempt_failed", LogCtx.NONE, "attempt" to attempts, "type" to t.javaClass.simpleName)
                continue
            }
            RoleGuess.parse(reply, speakers, input)?.let { return Decision(it, LLM, attempts, speakers) }
        }
        return Decision(RoleGuess.fallback(speakers), FALLBACK, attempts, speakers)
    }
}
