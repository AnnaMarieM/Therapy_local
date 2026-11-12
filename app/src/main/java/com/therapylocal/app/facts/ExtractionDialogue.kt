package com.therapylocal.app.facts

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMaps
import com.therapylocal.app.data.TranscriptFormat

/**
 * What the extraction reads: the transcript as a role-labelled dialogue, the same text the screen and the summary use ([TranscriptFormat]), with
 * the labels of the session's current speaker map. A speaker the map does not make therapist or patient (no map, one speaker) keeps a neutral
 * "Speaker n". Pure.
 */
object ExtractionDialogue {
    /** The label of a raw speaker id for [mapJson] (null: no map yet). The same label constants as the summary, so every reader of roles says the same. */
    fun label(segments: List<SegmentRow>, mapJson: String?): (String?) -> String =
        TranscriptFormat.roleLabel(
            mapJson?.let(SpeakerMaps::decode).orEmpty(),
            { if (it == Role.THERAPIST) SummaryConfig.LABEL_THERAPIST else SummaryConfig.LABEL_PATIENT },
            TranscriptFormat.neutralLabels(segments),
        )

    /** The windows of [segments] as dialogue under [mapJson] ([TranscriptWindows.byTurns]). */
    fun windows(segments: List<SegmentRow>, mapJson: String?, windowWords: Int, overlapWords: Int): List<TranscriptWindows.Span> =
        TranscriptWindows.byTurns(segments, windowWords, overlapWords, label(segments, mapJson))

    /** The text a quote must come from: the words of the window's segments, without labels or times. */
    fun groundingText(segments: List<SegmentRow>, segFrom: Int, segTo: Int): String =
        segments.filter { it.idx in segFrom..segTo && it.text.isNotBlank() }.joinToString(" ") { it.text }

    /**
     * Cache key of one window's extraction: prompt version, model, the speaker-map version whose labels the window carries, and the window text.
     * A swap changes the version, a different cut or transcript changes the text. [Pipeline] and [com.therapylocal.app.jobs.Reprocess] both use it.
     */
    fun cacheKey(promptVersion: String, model: String, speakerMapVersion: Int, windowText: String): String =
        "$promptVersion|$model|$speakerMapVersion|${Prompts.hash(windowText)}"
}
