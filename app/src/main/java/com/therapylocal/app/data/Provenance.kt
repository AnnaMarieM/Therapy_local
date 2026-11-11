package com.therapylocal.app.data

import android.content.ContentValues

/**
 * What a derived row was made from (docs/AD/AD-10.md): the transcript (by hash), the speaker map (by version), the prompt (by version) and the
 * model. Windows and facts carry these as the [COLUMNS] plus a `stale` flag; a session summary reuses the same columns.
 */
data class Provenance(val transcriptSha: String, val speakerMapVersion: Int, val promptVersion: String, val model: String) {
    /** Writes the columns of a new row, which is never stale. */
    fun put(values: ContentValues) {
        values.put("prov_transcript_sha", transcriptSha)
        values.put("prov_speaker_map_version", speakerMapVersion)
        values.put("prov_prompt_version", promptVersion)
        values.put("prov_model", model)
    }

    companion object {
        /** The speaker-map version of a session that has no map yet. */
        const val NO_MAP = 0

        const val COLUMNS = """prov_transcript_sha TEXT NOT NULL,
                 prov_speaker_map_version INTEGER NOT NULL,
                 prov_prompt_version TEXT NOT NULL,
                 prov_model TEXT NOT NULL,
                 stale INTEGER NOT NULL DEFAULT 0"""
    }
}
