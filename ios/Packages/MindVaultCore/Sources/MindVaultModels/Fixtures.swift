import Foundation

public enum Fixtures {
    public static let georgeID = UUID(uuidString: "11111111-1111-4111-8111-111111111111") ?? UUID()
    public static let mayaID = UUID(uuidString: "22222222-2222-4222-8222-222222222222") ?? UUID()
    public static let sessionGeorgeID = UUID(uuidString: "33333333-3333-4333-8333-333333333333") ?? UUID()
    public static let sessionMayaID = UUID(uuidString: "44444444-4444-4444-8444-444444444444") ?? UUID()

    public static let georgeTranscript = """
    Speaker 1: I saw my sister Maya at the market on Tuesday. We talked about the garden.
    Speaker 2: How did that feel?
    Speaker 1: It felt easier than last time. I want to call her again this weekend.
    """

    public static let mayaTranscript = """
    Speaker 1: Work has been loud this week. I keep waking up at four in the morning.
    Speaker 2: What helps when you wake up?
    Speaker 1: Walking around the block helps me settle down.
    """

    public static let clients: [Client] = [
        Client(
            id: georgeID,
            firstName: "George",
            lastName: "Miller",
            initialNote: "Prefers afternoon sessions.",
            age: 41,
            occupation: "Archivist",
            createdAt: Date(timeIntervalSince1970: 1_760_000_000),
            updatedAt: Date(timeIntervalSince1970: 1_760_300_000)
        ),
        Client(
            id: mayaID,
            firstName: "Maya",
            lastName: "Chen",
            initialNote: "Synthetic fixture.",
            age: 34,
            occupation: "Teacher",
            createdAt: Date(timeIntervalSince1970: 1_760_100_000),
            updatedAt: Date(timeIntervalSince1970: 1_760_200_000)
        )
    ]

    public static let sessions: [Session] = [
        Session(
            id: sessionGeorgeID,
            clientID: georgeID,
            externalID: "georgem_20261009-140000",
            startedAt: Date(timeIntervalSince1970: 1_760_300_000),
            endedAt: Date(timeIntervalSince1970: 1_760_301_200),
            duration: 1_200,
            chunkCount: 2,
            status: .saved,
            transcript: georgeTranscript,
            segments: [
                TranscriptSegment(
                    sessionID: sessionGeorgeID,
                    chunkIndex: 0,
                    startMs: 0,
                    endMs: 20_000,
                    text: "I saw my sister Maya at the market on Tuesday. We talked about the garden.",
                    createdAt: Date(timeIntervalSince1970: 1_760_300_000)
                ),
                TranscriptSegment(
                    sessionID: sessionGeorgeID,
                    chunkIndex: 1,
                    startMs: 20_000,
                    endMs: 40_000,
                    text: "It felt easier than last time. I want to call her again this weekend.",
                    createdAt: Date(timeIntervalSince1970: 1_760_300_020)
                )
            ],
            createdAt: Date(timeIntervalSince1970: 1_760_300_000)
        ),
        Session(
            id: sessionMayaID,
            clientID: mayaID,
            externalID: "mayac_20261012-100000",
            startedAt: Date(timeIntervalSince1970: 1_760_400_000),
            endedAt: Date(timeIntervalSince1970: 1_760_400_900),
            duration: 900,
            chunkCount: 1,
            status: .saved,
            transcript: mayaTranscript,
            createdAt: Date(timeIntervalSince1970: 1_760_400_000)
        )
    ]

    public static let facts: [Fact] = [
        Fact(
            clientID: georgeID,
            sessionID: sessionGeorgeID,
            category: .person,
            subject: "George",
            text: "George saw his sister Maya at the market on Tuesday.",
            evidence: "I saw my sister Maya at the market on Tuesday.",
            createdAt: Date(timeIntervalSince1970: 1_760_300_100)
        ),
        Fact(
            clientID: georgeID,
            sessionID: sessionGeorgeID,
            category: .goal,
            subject: "George",
            text: "George wants to call Maya again this weekend.",
            evidence: "I want to call her again this weekend.",
            createdAt: Date(timeIntervalSince1970: 1_760_300_200)
        ),
        Fact(
            clientID: mayaID,
            sessionID: sessionMayaID,
            category: .coping,
            subject: "Maya",
            text: "Walking around the block helps Maya settle down.",
            evidence: "Walking around the block helps me settle down.",
            createdAt: Date(timeIntervalSince1970: 1_760_400_100)
        )
    ]

    public static let chunks: [Chunk] = [
        Chunk(
            sessionID: sessionGeorgeID,
            index: 0,
            startMs: 0,
            endMs: 20_000,
            overlapMs: 0,
            text: "I saw my sister Maya at the market on Tuesday. We talked about the garden.",
            createdAt: Date(timeIntervalSince1970: 1_760_300_000)
        ),
        Chunk(
            sessionID: sessionGeorgeID,
            index: 1,
            startMs: 20_000,
            endMs: 40_000,
            overlapMs: 0,
            text: "It felt easier than last time. I want to call her again this weekend.",
            createdAt: Date(timeIntervalSince1970: 1_760_300_020)
        )
    ]

    public static let document = VaultDocument(
        clients: clients,
        sessions: sessions,
        chunks: chunks,
        facts: facts,
        savedAt: Date(timeIntervalSince1970: 1_760_500_000)
    )
}
