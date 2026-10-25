package com.therapylocal.app.ui.models

import java.util.UUID

data class SessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val duration: String,
    val summary: String,
    val topics: List<String> = emptyList(),
    val verbatimQuotes: List<String> = emptyList(),
    val actionItems: List<String> = emptyList()
)

data class Patient(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // e.g. "George M."
    val age: Int,
    val presenting: String,
    val lastSession: String,
    val sessionCount: Int,
    val preSessionNote: String,
    val sessions: List<SessionRecord> = emptyList(),
    val facts: List<String> = emptyList()
)

data class SurfacedQuote(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val topicTrigger: String,
    val sessionDate: String,
    val sessionTheme: String,
    val quote: String
)

data class ProcessingSessionInfo(
    val sessionId: String,
    val date: String,
    val stage: String,
    val isPendingEnqueue: Boolean = false
)
