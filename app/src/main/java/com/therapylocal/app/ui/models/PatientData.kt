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

object SeedData {
    fun getInitialPatients(): List<Patient> = listOf(
        Patient(
            id = "p-1",
            name = "George M.",
            age = 41,
            presenting = "Work-related anxiety, sleep disturbances",
            lastSession = "Oct 24, 2026",
            sessionCount = 8,
            preSessionNote = "Preparing for board review. Revisit sleep hygiene. Mentioned escalating tension with direct report David.",
            sessions = listOf(
                SessionRecord(
                    id = "s-1-1",
                    date = "Oct 24, 2026",
                    duration = "52 min",
                    summary = "Client explored intense pressure ahead of the board presentation. Physical symptoms (tight chest, waking at 3am) have increased.",
                    topics = listOf("Work anxiety", "Sleep hygiene", "Board review"),
                    verbatimQuotes = listOf(
                        "I feel like if I stumble once, they'll realize I shouldn't be in this seat.",
                        "Sleep is the first thing that goes. My mind just loops the spreadsheet."
                    ),
                    actionItems = listOf("Practice 4-7-8 breathing before bed", "Write down worry list at 6 PM")
                ),
                SessionRecord(
                    id = "s-1-2",
                    date = "Oct 17, 2026",
                    duration = "50 min",
                    summary = "Reviewed delegation strategies at work. Noticed pattern of taking on extra tasks to avoid interpersonal friction.",
                    topics = listOf("Delegation", "Boundaries", "Conflict avoidance"),
                    verbatimQuotes = listOf("It's just faster if I do it myself, but then I resent everyone.")
                )
            ),
            facts = listOf(
                "Wife: Sarah (married 12 yrs)",
                "Daughter: Maya (age 7)",
                "VP at logistics firm",
                "Insomnia peaks on Sunday nights",
                "Panic attack during Q2 presentation (April)"
            )
        ),
        Patient(
            id = "p-2",
            name = "Marta K.",
            age = 29,
            presenting = "Complicated grief (mother passed 8 mos ago)",
            lastSession = "Oct 22, 2026",
            sessionCount = 14,
            preSessionNote = "Anniversary of mother's passing approaching. Assess coping mechanisms and support network.",
            sessions = listOf(
                SessionRecord(
                    id = "s-2-1",
                    date = "Oct 22, 2026",
                    duration = "50 min",
                    summary = "Marta reflected on cleaning out her mother's apartment. Triggered waves of guilt regarding unanswered phone calls.",
                    topics = listOf("Grief processing", "Guilt", "Family dynamics"),
                    verbatimQuotes = listOf(
                        "I found her voicemails from last autumn. I still can't bring myself to listen.",
                        "Everyone says time heals, but it just feels like the world is moving on without her."
                    ),
                    actionItems = listOf("Designate 20-minute daily grief container", "Gentle walk when overwhelmed")
                ),
                SessionRecord(
                    id = "s-2-2",
                    date = "Oct 15, 2026",
                    duration = "48 min",
                    summary = "Discussed isolation during weekends. Relationship with father remains distant.",
                    topics = listOf("Isolation", "Father relationship"),
                    verbatimQuotes = listOf("My dad just pretends nothing happened.")
                )
            ),
            facts = listOf(
                "Mother passed Nov 2025 (cancer)",
                "Only child; strained relationship with father",
                "Works in UX design (remote)",
                "Inherited mother's cat (Milo)",
                "Avoids driving past St. Jude's hospital"
            )
        ),
        Patient(
            id = "p-3",
            name = "Daniel S.",
            age = 35,
            presenting = "Relationship boundary setting",
            lastSession = "Oct 19, 2026",
            sessionCount = 5,
            preSessionNote = "Follow up on conversation with partner re: finances and shared household responsibilities.",
            sessions = listOf(
                SessionRecord(
                    id = "s-3-1",
                    date = "Oct 19, 2026",
                    duration = "45 min",
                    summary = "Daniel reported holding a calm conversation with Liam about expenses. Felt uneasy not apologizing immediately.",
                    topics = listOf("Partner communication", "Guilt", "Financial autonomy"),
                    verbatimQuotes = listOf("I stated my boundary and then felt like the worst person in the room for an hour.")
                )
            ),
            facts = listOf(
                "Partner: Liam (together 4 yrs)",
                "Freelance photographer",
                "Chronic guilt when saying 'no'",
                "Oldest of four siblings"
            )
        )
    )
}
