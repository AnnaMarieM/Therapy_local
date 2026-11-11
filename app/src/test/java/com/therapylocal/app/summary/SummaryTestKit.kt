package com.therapylocal.app.summary

import com.therapylocal.app.data.Provenance
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMapRow
import com.therapylocal.app.data.SpeakerMaps
import com.therapylocal.app.data.SummaryRow
import com.therapylocal.app.data.TranscriptHash
import com.therapylocal.app.data.TranscriptVersion

/** In-memory [SummaryRepo]; the speaker maps are kept as versions like `speaker_maps`, so a swap goes through [SpeakerMaps.appendSwap]. */
class FakeSummaryRepo(var segments: List<SegmentRow> = emptyList()) : SummaryRepo {
    val maps = ArrayList<SpeakerMapRow>()
    var stored: SummaryRow? = null

    override fun segments(session: String) = segments
    override fun currentTranscript(session: String) = TranscriptVersion(session, 1, TranscriptHash.of(segments), null, null, 0L)
    override fun currentSpeakerMap(session: String) = maps.lastOrNull()
    override fun summary(session: String) = stored
    override fun putSummary(row: SummaryRow) { stored = row }
    /** The same rule as the UPDATE in `MindStore.refreshSummaryStale`. */
    override fun refreshSummaryStale(session: String, promptVersion: String, model: String) {
        val p = stored?.provenance ?: return
        val stale = p.transcriptSha != currentTranscript(session).sha256 || p.speakerMapVersion != (currentSpeakerMap(session)?.version ?: Provenance.NO_MAP) ||
            p.promptVersion != promptVersion || p.model != model
        stored = stored?.copy(stale = stale)
    }

    fun addMap(json: String, source: String = "llm") { maps += SpeakerMapRow("s", maps.size + 1, json, source, 0.9, "[]", 0L) }

    /** The "Is this incorrect?" swap, exactly as the app does it. */
    fun swap() = SpeakerMaps.appendSwap(currentSpeakerMap("s")) { json, source, _, _ -> addMap(json, source); maps.size }
}

/** A short two-speaker session: S1 is the therapist, S0 the patient. */
fun shortSession(): List<SegmentRow> = listOf(
    SegmentRow(1, 0, 4_000, "How was your week?", "S1"),
    SegmentRow(2, 4_000, 9_000, "Hard, I could not sleep at all.", "S0"),
    SegmentRow(3, 9_000, 12_000, "Tell me more about the nights.", "S1"),
)

const val MAP_S1_THERAPIST = """{"S0":"patient","S1":"therapist"}"""

/** A prompt set with recognisable markers; the system prompts carry the format so tests can check it reaches the model. */
fun testPrompts() = SummaryPrompts("FINAL-SYSTEM\nformat", "FINAL-USER {{TEXT}}", "NOTES-SYSTEM", "NOTES-USER {{PART}}/{{PARTS}} {{TEXT}}")

/** Records every call and answers by role of the prompt. */
class RecordingAsk(private val final: String = "Subjective: the client slept badly this week and says so plainly.", private val notes: (Int) -> String = { "notes $it" }) : SummaryAsk {
    class Call(val system: String, val user: String, val maxOut: Int)

    val calls = ArrayList<Call>()
    var notesCalls = 0

    override fun ask(system: String, user: String, maxOutputTokens: Int): String {
        calls += Call(system, user, maxOutputTokens)
        return if (system.startsWith("NOTES")) notes(++notesCalls) else final
    }
}
