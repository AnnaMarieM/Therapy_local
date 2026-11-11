package com.therapylocal.app.data

import android.content.ContentValues

/**
 * What an item derived from a session's inputs was made from (docs/AD/AD-10.md): the transcript (by content hash), the speaker map (by version),
 * the prompt (by version hash, or the cut configuration for windows) and the model. One shape for every derived row: a table that holds derived
 * items carries the [COLUMNS] and is listed in [Derived]; windows and facts do today, a session summary joins by the same route.
 *
 * Unknown parts (rows that predate AD-10) are [UNKNOWN_TEXT] and [UNKNOWN_VERSION]. An unknown part is never the reason for a row to be stale:
 * only a known value that differs from the current one is.
 */
data class Provenance(
    val transcriptSha: String,
    val speakerMapVersion: Int,
    val promptVersion: String,
    val model: String,
) {
    /** Writes the provenance columns of a new row; a new row is never stale. */
    fun put(values: ContentValues) {
        values.put("prov_transcript_sha", transcriptSha)
        values.put("prov_speaker_map_version", speakerMapVersion)
        values.put("prov_prompt_version", promptVersion)
        values.put("prov_model", model)
        values.put("stale", 0)
    }

    companion object {
        const val UNKNOWN_TEXT = ""
        const val UNKNOWN_VERSION = -1

        /** The speaker-map version of a session that has no map yet. */
        const val NO_MAP = 0

        /** Column definitions shared by CREATE TABLE and the version 5 upgrade of every derived table. */
        val COLUMNS: List<String> = listOf(
            "prov_transcript_sha TEXT NOT NULL DEFAULT ''",
            "prov_speaker_map_version INTEGER NOT NULL DEFAULT -1",
            "prov_prompt_version TEXT NOT NULL DEFAULT ''",
            "prov_model TEXT NOT NULL DEFAULT ''",
            "stale INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/** The kinds of derived rows. [table] needs `session_id` and the [Provenance.COLUMNS]. */
enum class Derived(val table: String, val usesSpeakerMap: Boolean) {
    /** Cut from the text only; roles are resolved at read time, so a speaker swap does not change them. */
    WINDOWS("windows", false),

    /** Extracted from window text only (see [WINDOWS]). */
    FACTS("facts", false),
}

/** The prompt and model the current configuration would use for one kind; null means "not compared". */
data class Expectation(val promptVersion: String? = null, val model: String? = null)

object Staleness {
    /** True when [stored] was made from something other than [current] that matters for [kind]. Unknown stored parts never make a row stale. */
    fun isStale(kind: Derived, stored: Provenance, current: Provenance, expect: Expectation = Expectation()): Boolean =
        isStale(kind.usesSpeakerMap, stored, current, expect)

    /** The rule itself: [usesSpeakerMap] is whether the kind's content depends on who is therapist and patient. */
    fun isStale(usesSpeakerMap: Boolean, stored: Provenance, current: Provenance, expect: Expectation = Expectation()): Boolean =
        differs(stored.transcriptSha, current.transcriptSha) ||
            (usesSpeakerMap && stored.speakerMapVersion != Provenance.UNKNOWN_VERSION && stored.speakerMapVersion != current.speakerMapVersion) ||
            (expect.promptVersion != null && differs(stored.promptVersion, expect.promptVersion)) ||
            (expect.model != null && differs(stored.model, expect.model))

    private fun differs(stored: String, current: String) = stored != Provenance.UNKNOWN_TEXT && stored != current
}

/** A session as the rebuild orders it. */
data class SessionStamp(val id: String, val clientId: String, val startedAt: Long)

object Rebuild {
    /**
     * The sessions to rebuild, in the order they must be queued: per client oldest first (a session's facts are planned against the earlier
     * ones, AD-10 and MEMORY D4), sessions with open jobs left out.
     */
    fun order(stale: Collection<SessionStamp>, busy: Set<String>): List<String> =
        stale.filter { it.id !in busy }.sortedWith(compareBy({ it.clientId }, { it.startedAt }, { it.id })).map { it.id }
}
