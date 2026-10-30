package com.therapylocal.app.ui.models

import java.util.UUID

/** A fact as the profile lists it. [mentionCount] counts the later sessions it came up in again. */
data class FactItem(
    val id: Long,
    val heading: String,
    val content: String,
    val date: String,
    val mentionCount: Int = 0
)

/** One recorded session. Its transcript is loaded on demand. */
data class SessionItem(
    val id: String,
    val date: String,
    val duration: String
)

/** One transcript segment; [time] is its offset into the session ("mm:ss"). */
data class TranscriptLine(val idx: Int, val time: String, val text: String)

/** Where a fact was said: the segments around it, with [segIdx] the one holding the evidence. */
data class FactOccurrence(
    val sessionId: String,
    val date: String,
    val first: Boolean,
    val segIdx: Int,
    val lines: List<TranscriptLine>
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
    val facts: List<FactItem> = emptyList()
)

data class ProcessingSessionInfo(
    val sessionId: String,
    val date: String,
    val stage: String,
    val isPendingEnqueue: Boolean = false
)
