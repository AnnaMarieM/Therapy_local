package com.therapylocal.app.data

/**
 * The transcript as text, one block per utterance, blocks separated by a blank line:
 * ```
 * [00:03:12]
 * Therapist: text
 * ```
 * The time is from the start of the session; hours have at least two digits. Labels come from the caller, so the screen can use string resources.
 * Pure Kotlin.
 */
object TranscriptFormat {
    /** Utterances without text are left out. */
    fun format(segments: List<SegmentRow>, label: (speaker: String?) -> String): String =
        segments.filter { it.text.isNotBlank() }.joinToString("\n\n") { "[${clock(it.startMs)}]\n${label(it.speaker)}: ${it.text.trim()}" }

    /** hh:mm:ss, hours growing past two digits when needed; a negative time counts as 0. */
    fun clock(ms: Long): String {
        val s = maxOf(ms, 0L) / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }

    /** "Speaker 1", "Speaker 2", ... in order of first appearance in [segments]; no speaker is "Speaker ?". */
    fun neutralLabels(segments: List<SegmentRow>): (String?) -> String {
        val order = LinkedHashMap<String, Int>()
        for (s in segments) if (s.speaker != null && s.speaker !in order) order[s.speaker] = order.size + 1
        return { sp -> order[sp]?.let { "Speaker $it" } ?: "Speaker ?" }
    }

    /**
     * Therapist and patient get their name from [roleName] (the screen passes string resources); everyone else (other, not in [map], no speaker)
     * gets the [neutral] label, so a speaker without a role keeps their number.
     */
    fun roleLabel(map: Map<String, Role>, roleName: (Role) -> String, neutral: (String?) -> String): (String?) -> String = { sp ->
        when (val r = sp?.let(map::get)) {
            Role.THERAPIST, Role.PATIENT -> roleName(r)
            else -> neutral(sp)
        }
    }
}
