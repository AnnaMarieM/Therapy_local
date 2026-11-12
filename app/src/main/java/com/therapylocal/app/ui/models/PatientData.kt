package com.therapylocal.app.ui.models

import com.therapylocal.app.models.SpeechLanguage
import java.util.UUID

/** A fact as the profile lists it. [mentionCount] counts the later sessions it came up in again. */
data class FactItem(
    val id: Long,
    val heading: String,
    val content: String,
    val date: String,
    val mentionCount: Int = 0,
    val scope: String = "profile",
    val category: String = "",
)

/** One recorded session. Its transcript is loaded on demand. */
data class SessionItem(
    val id: String,
    val date: String,
    val duration: String
)

/**
 * One transcript segment; [time] is its offset into the session ("mm:ss"), [role] who said it as the transcript view labels it (the role
 * of the session's current speaker map, "Speaker 1" for anyone without one; null when the recording was not diarized).
 */
data class TranscriptLine(val idx: Int, val time: String, val text: String, val role: String? = null, val startMs: Long = 0, val endMs: Long = 0)

/** Where a fact was said: segments [segFrom]..[segTo] of [sessionId], at [startMs]..[endMs] (AD-14); [first] is the fact's own first occurrence. */
data class FactMention(
    val sessionId: String,
    val date: String,
    val first: Boolean,
    val segFrom: Int,
    val segTo: Int = segFrom,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

/**
 * A search result in one session: a stretch of transcript (no [title]) or a fact. Opening it shows segments [segFrom]..[segTo],
 * with [highlight] (a fact's evidence segment) marked.
 */
data class SearchHit(
    val sessionId: String,
    val date: String,
    val title: String?,
    val snippet: String,
    val segFrom: Int,
    val segTo: Int,
    val highlight: Int? = null
)

data class Patient(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // e.g. "George M."
    val lastSession: String,
    val sessionCount: Int,
    val sessions: List<SessionItem> = emptyList(),
    val facts: List<FactItem> = emptyList(),
    /** What the client speaks in sessions; picks the speech recogniser. */
    val language: SpeechLanguage = SpeechLanguage.English,
)

data class ProcessingSessionInfo(
    val sessionId: String,
    val date: String,
    val stage: String,
    val isPendingEnqueue: Boolean = false,
    /** The evidence, from what was measured: how far along, how fast, how long; shown in turn once the status has been looked at. */
    val details: List<String> = emptyList(),
)
