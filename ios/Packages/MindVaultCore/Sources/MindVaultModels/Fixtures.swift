import Foundation

/// The screen-tour fixtures, aligned with the Android tour's fake data
/// (`app/src/androidTest/java/com/therapylocal/app/screens/ScreenList.kt`,
/// `seed()`): the same four invented clients, the same five sessions at the
/// same instants, the same lines and the same four facts, so a CI screenshot
/// of either app shows the same content and the parity compare (#133) reads
/// text, not inventions. Identifiers are fixed UUIDs so tests and the
/// screenshot harness can name them.
///
/// Two Android tour details have no slot in the iOS `VaultDocument` yet and are
/// documented instead of faked (see `scratchpad/parity/README.md`): the
/// mention of the "Runs again" fact in Ana's second session, and the fact
/// categories (Android Health/Family/Work map onto the nine-case taxonomy
/// below).
public enum Fixtures {
    // MARK: identifiers

    public static let anaID = UUID(uuidString: "11111111-1111-4111-8111-111111111111") ?? UUID()
    public static let benID = UUID(uuidString: "22222222-2222-4222-8222-222222222222") ?? UUID()
    public static let claraID = UUID(uuidString: "33333333-3333-4333-8333-333333333333") ?? UUID()
    public static let zoeID = UUID(uuidString: "44444444-4444-4444-8444-444444444444") ?? UUID()

    public static let sessionAna1ID = UUID(uuidString: "55555555-5555-4555-8555-555555555555") ?? UUID()
    public static let sessionAna2ID = UUID(uuidString: "66666666-6666-4666-8666-666666666666") ?? UUID()
    public static let sessionAna3ID = UUID(uuidString: "77777777-7777-4777-8777-777777777777") ?? UUID()
    public static let sessionBen1ID = UUID(uuidString: "88888888-8888-4888-8888-888888888888") ?? UUID()
    public static let sessionClara1ID = UUID(uuidString: "99999999-9999-4999-8999-999999999999") ?? UUID()

    // MARK: the tour's instants

    /// The Android tour's `DAY_1` (1_767_225_600_000 ms): 2026-01-01 00:00 UTC.
    public static let day1 = Date(timeIntervalSince1970: 1_767_225_600)
    /// The Android tour's `WEEK`: 7 × 24 h.
    public static let week: TimeInterval = 7 * 86_400

    // MARK: the tour's lines

    public static let lines1: [String] = [
        "Good to see you again.", "How was the month?", "Busy, but better.",
        "I took up running again in May.", "That sounds like a change.",
        "It clears my head before work.", "And the evenings?", "Quieter now, mostly.",
    ]
    public static let lines2: [String] = [
        "Where would you like to start?", "With the mornings, I think.",
        "I am still running three mornings a week.", "What keeps you going?",
    ]
    public static let lines3: [String] = [
        "How has the week been?", "My sister visited, which was nice.",
        "The new job is a lot, but I am learning.", "Sleep has been better.",
    ]

    /// The Android tour stores each line as its own five-second segment
    /// (`SegmentRow(i, i * 5_000, i * 5_000 + 5_000, line)`), timestamped with
    /// the session start — an explicit integral date, because a default `now`
    /// would carry sub-second parts that the JSON round-trip drops and the
    /// document-equality tests would catch.
    public static func segments(_ lines: [String], sessionID: UUID, createdAt: Date) -> [TranscriptSegment] {
        lines.enumerated().map { index, text in
            TranscriptSegment(
                sessionID: sessionID,
                chunkIndex: 0,
                startMs: index * 5_000,
                endMs: index * 5_000 + 5_000,
                text: text,
                createdAt: createdAt
            )
        }
    }

    public static func transcript(_ lines: [String]) -> String {
        lines.joined(separator: "\n")
    }

    public static let anaTranscript1 = transcript(lines1)
    public static let anaTranscript2 = transcript(lines2)
    public static let anaTranscript3 = transcript(lines3)
    /// Android reuses `LINES_2` for Ben's session and `LINES_3` for Clara's.
    public static let benTranscript = anaTranscript2
    public static let claraTranscript = anaTranscript3

    // MARK: clients

    /// The tour's four invented clients, no notes (the Android fake repo has
    /// none). `updatedAt` is staggered so the home reel is deterministic —
    /// Ana first, in the Android tour's order.
    public static let clients: [Client] = [
        Client(
            id: anaID,
            firstName: "Ana",
            createdAt: day1,
            updatedAt: day1.addingTimeInterval(29 * 86_400)
        ),
        Client(
            id: benID,
            firstName: "Ben",
            lastName: "C.",
            createdAt: day1,
            updatedAt: day1.addingTimeInterval(28 * 86_400)
        ),
        Client(
            id: claraID,
            firstName: "Clara",
            lastName: "D.",
            createdAt: day1,
            updatedAt: day1.addingTimeInterval(27 * 86_400)
        ),
        Client(
            id: zoeID,
            firstName: "Zoe",
            lastName: "K.",
            createdAt: day1,
            updatedAt: day1.addingTimeInterval(26 * 86_400)
        ),
    ]

    // MARK: sessions

    /// The tour's five sessions: Ana 2026-01-01 / +1 week / +2 weeks, Ben +3
    /// weeks, Clara +4 weeks, Zoe none. Durations follow the segments (5 s
    /// each), and only Ana's first session carries a transcript window, like
    /// Android's `replaceWindows(S1, …)`.
    public static let sessions: [Session] = [
        Session(
            id: sessionAna1ID,
            clientID: anaID,
            externalID: "ana_20260101-000000",
            startedAt: day1,
            endedAt: day1.addingTimeInterval(40),
            duration: 40,
            chunkCount: 1,
            status: .saved,
            transcript: anaTranscript1,
            segments: segments(lines1, sessionID: sessionAna1ID, createdAt: day1),
            createdAt: day1
        ),
        Session(
            id: sessionAna2ID,
            clientID: anaID,
            externalID: "ana_20260108-000000",
            startedAt: day1.addingTimeInterval(week),
            endedAt: day1.addingTimeInterval(week + 20),
            duration: 20,
            chunkCount: 0,
            status: .saved,
            transcript: anaTranscript2,
            segments: segments(lines2, sessionID: sessionAna2ID, createdAt: day1.addingTimeInterval(week)),
            createdAt: day1.addingTimeInterval(week)
        ),
        Session(
            id: sessionAna3ID,
            clientID: anaID,
            externalID: "ana_20260115-000000",
            startedAt: day1.addingTimeInterval(2 * week),
            endedAt: day1.addingTimeInterval(2 * week + 20),
            duration: 20,
            chunkCount: 0,
            status: .saved,
            transcript: anaTranscript3,
            segments: segments(lines3, sessionID: sessionAna3ID, createdAt: day1.addingTimeInterval(2 * week)),
            createdAt: day1.addingTimeInterval(2 * week)
        ),
        Session(
            id: sessionBen1ID,
            clientID: benID,
            externalID: "ben_20260122-000000",
            startedAt: day1.addingTimeInterval(3 * week),
            endedAt: day1.addingTimeInterval(3 * week + 20),
            duration: 20,
            chunkCount: 0,
            status: .saved,
            transcript: benTranscript,
            segments: segments(lines2, sessionID: sessionBen1ID, createdAt: day1.addingTimeInterval(3 * week)),
            createdAt: day1.addingTimeInterval(3 * week)
        ),
        Session(
            id: sessionClara1ID,
            clientID: claraID,
            externalID: "clara_20260129-000000",
            startedAt: day1.addingTimeInterval(4 * week),
            endedAt: day1.addingTimeInterval(4 * week + 20),
            duration: 20,
            chunkCount: 0,
            status: .saved,
            transcript: claraTranscript,
            segments: segments(lines3, sessionID: sessionClara1ID, createdAt: day1.addingTimeInterval(4 * week)),
            createdAt: day1.addingTimeInterval(4 * week)
        ),
    ]

    /// The tour's one transcript window (`WindowRow(0, 0, LINES_1.lastIndex,
    /// LINES_1.joinToString(" "), QUERY_VEC, "fake")`): Ana's first session,
    /// spanning all eight segments, text joined with spaces.
    public static let chunks: [Chunk] = [
        Chunk(
            sessionID: sessionAna1ID,
            index: 0,
            startMs: 0,
            endMs: lines1.count * 5_000,
            overlapMs: 0,
            text: lines1.joined(separator: " "),
            createdAt: day1
        ),
    ]

    // MARK: facts

    /// The tour's four facts. Android's fact categories map onto the iOS
    /// nine-case taxonomy: Health → coping, Family → relationship, Work →
    /// event (see `scratchpad/parity/README.md`). `subject` is the Android
    /// heading (the row the profile shows), `text` its content.
    public static let facts: [Fact] = [
        Fact(
            clientID: anaID,
            sessionID: sessionAna1ID,
            category: .coping,
            subject: "Runs again",
            text: "Ana took up running again in May.",
            evidence: lines1[3],
            createdAt: day1
        ),
        Fact(
            clientID: anaID,
            sessionID: sessionAna3ID,
            category: .relationship,
            subject: "Sister visited",
            text: "Ana's sister stayed for a week and it went well.",
            evidence: lines3[1],
            createdAt: day1.addingTimeInterval(2 * week)
        ),
        Fact(
            clientID: anaID,
            sessionID: sessionAna3ID,
            category: .event,
            subject: "New job",
            text: "Ana started a new job and is still finding her feet.",
            evidence: lines3[2],
            createdAt: day1.addingTimeInterval(2 * week)
        ),
        Fact(
            clientID: anaID,
            sessionID: sessionAna3ID,
            category: .coping,
            subject: "Sleeping better",
            text: "Sleep has improved over the last weeks.",
            evidence: lines3[3],
            createdAt: day1.addingTimeInterval(2 * week)
        ),
    ]

    public static let document = VaultDocument(
        clients: clients,
        sessions: sessions,
        chunks: chunks,
        facts: facts,
        savedAt: day1.addingTimeInterval(30 * 86_400)
    )
}
