package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.Provenance
import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMapRow
import com.therapylocal.app.data.SpeakerMaps
import com.therapylocal.app.data.SummaryRow
import com.therapylocal.app.data.TranscriptFormat
import com.therapylocal.app.data.TranscriptHash
import com.therapylocal.app.data.TranscriptVersion
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.jobs.JobFailure
import com.therapylocal.app.jobs.JobStopped
import com.therapylocal.app.jobs.ModelSwitched

/** none: no summary and none being made; generating: a SUMMARY job is open; ready: current; stale: made from inputs that have changed since. */
enum class SummaryState { None, Generating, Ready, Stale }

/** One section of a stored summary, as the model wrote it; [label] is empty for text before the first heading. */
data class SummarySection(val label: String, val text: String)

/**
 * What a screen needs of the summary of one session. [text] is the last summary made, if any, also while [state] is [SummaryState.Generating]
 * or [SummaryState.Stale] (the screen decides whether to show it); [sections] is [text] split at the headings of [SummaryConfig.SECTIONS].
 */
data class SessionSummary(val sessionId: String, val state: SummaryState, val text: String?, val sections: List<SummarySection>, val generatedAt: Long?) {
    companion object {
        fun none(sessionId: String, state: SummaryState = SummaryState.None) = SessionSummary(sessionId, state, null, emptyList(), null)
    }
}

/** The storage a summary needs: the transcript and roles it reads and its own row. [com.therapylocal.app.data.MindStore] implements it. */
interface SummaryRepo {
    fun segments(session: String): List<SegmentRow>
    fun currentTranscript(session: String): TranscriptVersion?
    fun currentSpeakerMap(session: String): SpeakerMapRow?
    fun summary(session: String): SummaryRow?
    fun putSummary(row: SummaryRow)
    fun refreshSummaryStale(session: String, promptVersion: String, model: String)
}

/**
 * The inputs a summary is made from. [transcriptHash] covers the stored utterances (index, raw speaker id, text) and not the roles;
 * [speakerMapVersion] is the version of the roles used (0 when there is no map); [segments] are the utterances with the role written in the text.
 */
class SummaryInputs(val transcriptHash: String, val speakerMapVersion: Int, val segments: List<SegmentRow>) {
    companion object {
        fun of(repo: SummaryRepo, session: String): SummaryInputs {
            val raw = repo.segments(session)
            val map = repo.currentSpeakerMap(session)
            return SummaryInputs(repo.currentTranscript(session)?.sha256 ?: TranscriptHash.of(raw), map?.version ?: 0, labelled(raw, map?.let { SpeakerMaps.decode(it.mapJson) }.orEmpty()))
        }

        /** Text of every utterance prefixed with the role of its speaker; others are "Speaker 1", "Speaker 2"... A transcript without any speaker stays plain. */
        fun labelled(raw: List<SegmentRow>, map: Map<String, Role>): List<SegmentRow> {
            val spoken = raw.filter { it.text.isNotBlank() }
            if (spoken.none { it.speaker != null }) return spoken
            val order = LinkedHashMap<String, Int>()
            for (s in spoken) if (s.speaker != null && s.speaker !in order) order[s.speaker] = order.size + 1
            val neutral: (String?) -> String = { sp -> order[sp]?.let { "${SummaryConfig.LABEL_SPEAKER} $it" } ?: "${SummaryConfig.LABEL_SPEAKER} ?" }
            val label = TranscriptFormat.roleLabel(map, { if (it == Role.THERAPIST) SummaryConfig.LABEL_THERAPIST else SummaryConfig.LABEL_PATIENT }, neutral)
            return spoken.map { it.copy(text = "${label(it.speaker)}: ${it.text.trim()}") }
        }
    }
}

/**
 * The summary of a session: its state, its making, and its staleness. Pure Kotlin apart from the seams ([SummaryRepo], [SummaryMethod],
 * [SummaryAsk]), so it is unit-tested on the JVM.
 *
 * Staleness is the stored flag of AD-10: [SummaryRepo.refreshSummaryStale] compares the summary's provenance (transcript hash, speaker-map
 * version, prompt version, model) with the current ones, and a swap of the roles is just a new speaker-map version. See docs/AD/AD-12.md.
 */
class SessionSummaries(private val repo: SummaryRepo, private val clock: () -> Long = System::currentTimeMillis) {
    /** [generating] is whether a SUMMARY job is open for the session (the queue knows; this class does not). */
    fun view(session: String, promptVersion: String, model: String, generating: Boolean): SessionSummary {
        repo.refreshSummaryStale(session, promptVersion, model)
        val row = repo.summary(session)
        val state = when {
            generating -> SummaryState.Generating
            row == null -> SummaryState.None
            row.stale -> SummaryState.Stale
            else -> SummaryState.Ready
        }
        return if (row == null) SessionSummary.none(session, state) else SessionSummary(session, state, row.text, SummarySections.parse(row.text), row.createdAt)
    }

    /** The roles changed (the swap stored a new speaker-map version) or something else did: sets the flag. A session without a summary is left alone. */
    fun refreshStale(session: String, promptVersion: String, model: String) = repo.refreshSummaryStale(session, promptVersion, model)

    /**
     * Makes the summary of [session] with [method] and stores it with its provenance. The inputs are read once at the start, so a swap during
     * the run leaves the stored summary stale, which is the truth. Returns false when the session has no words (nothing is stored). Throws
     * [JobFailure] ("LLM") when the model gave no usable summary after [SummaryConfig.ATTEMPTS] tries per call; an engine that died, a stop
     * request and a model switch propagate, as in the other handlers.
     */
    fun build(session: String, prompts: SummaryPrompts, model: String, method: SummaryMethod, ask: SummaryAsk, step: (Int, Int) -> Unit = { _, _ -> }): BuildResult? {
        val inputs = SummaryInputs.of(repo, session)
        val out = method.summarize(inputs.segments, prompts, retrying(ask), step) ?: return null
        val text = clean(out.text)
        if (text.length < SummaryConfig.MIN_REPLY_CHARS) throw JobFailure("LLM")
        repo.putSummary(SummaryRow(session, text, Provenance(inputs.transcriptHash, inputs.speakerMapVersion, prompts.version(), model), method.id, false, clock()))
        return BuildResult(out.calls, text.length, inputs.speakerMapVersion, out.forcedSplits, out.levels)
    }

    class BuildResult(val calls: Int, val chars: Int, val speakerMapVersion: Int, val forcedSplits: Int = 0, val levels: Int = 0)

    /** Each call is tried [SummaryConfig.ATTEMPTS] times; a blank reply or a failure is a miss. Fatal ones pass straight through. */
    private fun retrying(ask: SummaryAsk) = SummaryAsk { system, user, maxOut ->
        var last: Throwable? = null
        for (attempt in 1..SummaryConfig.ATTEMPTS) {
            try {
                val reply = ask.ask(system, user, maxOut)
                if (reply.isNotBlank()) return@SummaryAsk reply
            } catch (t: Throwable) {
                if (fatal(t)) throw t
                last = t
            }
        }
        throw JobFailure("LLM").also { e -> last?.let(e::addSuppressed) }
    }

    private fun fatal(t: Throwable) = t is EngineDied || t is EngineUnavailable || t is JobStopped || t is ModelSwitched || (t is Error && t !is OutOfMemoryError)

    companion object {
        /** Code fences and surrounding blank space removed. */
        fun clean(reply: String): String = reply.trim().removePrefix("```markdown").removePrefix("```text").removePrefix("```").removeSuffix("```").trim()
    }
}

/** Splits a summary at the headings of [SummaryConfig.SECTIONS]. Pure Kotlin. */
object SummarySections {
    fun parse(text: String): List<SummarySection> {
        val labels = SummaryConfig.SECTIONS.map { it.label }
        val heading = Regex("^\\s*[*#_ ]*(${labels.joinToString("|") { Regex.escape(it) }})[*_ ]*(?::[*_ ]*(.*)|)$", RegexOption.IGNORE_CASE)
        val out = ArrayList<SummarySection>()
        var label = ""
        val body = StringBuilder()
        fun flush() {
            val t = body.toString().trim()
            if (label.isNotEmpty() || t.isNotEmpty()) out += SummarySection(label, t)
            body.setLength(0)
        }
        for (line in text.lines()) {
            val m = heading.matchEntire(line)
            if (m != null) {
                flush()
                label = labels.first { it.equals(m.groupValues[1], ignoreCase = true) }
                body.append(m.groupValues[2])
            } else {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
        }
        flush()
        return out
    }
}
