package com.therapylocal.app.ui.logic

import com.therapylocal.app.data.Role
import com.therapylocal.app.ui.models.TranscriptLine

/**
 * One transcript line as the screen shows it in a conversation. [header] is true when the line starts a turn (the speaker differs from the
 * line before, or it is the first one shown): only then the speaker's name is written. [role] is the therapist or the patient from the session's
 * speaker map, null for anyone else; [number] is the 1-based speaker number for the neutral label ("Speaker 2"), null without a speaker.
 */
data class TurnRow(val line: TranscriptLine, val role: Role?, val number: Int?, val header: Boolean)

/** Lines to turns. Pure Kotlin. */
object TranscriptTurns {
    /**
     * [map] is the raw diarization id to role (`S0` to therapist), empty when the roles were not guessed. Lines without a speaker (the chunk-path
     * fallback) never start a turn: they show as plain lines, as before. The speaker number comes from the id (`S0` is 1) so an excerpt of a
     * session numbers the speakers like the whole transcript; an id that is not `S<n>` is numbered by first appearance.
     */
    fun rows(lines: List<TranscriptLine>, map: Map<String, Role>): List<TurnRow> {
        val seen = LinkedHashMap<String, Int>()
        return lines.mapIndexed { i, line ->
            val sp = line.speaker
            if (sp != null && sp !in seen) seen[sp] = seen.size + 1
            val number = sp?.let { number(it) ?: seen.getValue(it) }
            TurnRow(line, sp?.let(map::get), number, header = sp != null && (i == 0 || lines[i - 1].speaker != sp))
        }
    }

    private fun number(id: String): Int? = if (id.length > 1 && id[0] == 'S') id.substring(1).toIntOrNull()?.plus(1) else null
}
