package com.therapylocal.app.data

/**
 * Who a fact came from, resolved when it is read (docs/AD/AD-14.md). A fact stores the raw diarization id of the speaker of its quote
 * (`facts.speaker`, "S0"); the role is looked up in the session's current speaker map every time, so a roles swap changes what is shown
 * without extracting again. Pure Kotlin.
 */
object FactSpeakers {
    /** The role of [speaker] in [map]; null when it cannot be resolved (no raw id because the recording was not diarized, no map yet, or an id the map lacks). */
    fun roleOf(speaker: String?, map: Map<String, Role>?): Role? = if (speaker == null || map == null) null else map[speaker]

    /**
     * Whether a fact of [role] is shown. By default only the patient's: a therapist's or another speaker's fact is hidden. A fact whose role
     * cannot be resolved is shown, because nothing says it is not the patient's (a session with one speaker has no map). [includeTherapist]
     * shows every fact.
     */
    fun visible(role: Role?, includeTherapist: Boolean): Boolean = includeTherapist || role == null || role == Role.PATIENT

    /** The facts of [rows] shown under [maps] (session to current map), in order. */
    fun <T> filter(rows: List<T>, includeTherapist: Boolean, sessionOf: (T) -> String, speakerOf: (T) -> String?, maps: Map<String, Map<String, Role>>): List<T> =
        if (includeTherapist) rows else rows.filter { visible(roleOf(speakerOf(it), maps[sessionOf(it)]), false) }
}
