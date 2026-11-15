import Foundation

public enum SessionStatus: String, Codable, CaseIterable, Sendable {
    case recording
    case paused
    case transcribing
    case review
    case saved
    case discarded
}

public struct TranscriptSegment: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var sessionID: UUID?
    public var chunkIndex: Int
    public var startMs: Int
    public var endMs: Int
    public var text: String
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        sessionID: UUID? = nil,
        chunkIndex: Int,
        startMs: Int,
        endMs: Int,
        text: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.sessionID = sessionID
        self.chunkIndex = chunkIndex
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
        self.createdAt = createdAt
    }

    public var durationMs: Int {
        max(0, endMs - startMs)
    }
}

public struct Session: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var clientID: UUID
    public var externalID: String
    public var startedAt: Date
    public var endedAt: Date?
    public var duration: TimeInterval
    public var chunkCount: Int
    public var status: SessionStatus
    public var transcript: String
    public var segments: [TranscriptSegment]
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        clientID: UUID,
        externalID: String,
        startedAt: Date = Date(),
        endedAt: Date? = nil,
        duration: TimeInterval = 0,
        chunkCount: Int = 0,
        status: SessionStatus = .recording,
        transcript: String = "",
        segments: [TranscriptSegment] = [],
        createdAt: Date? = nil
    ) {
        self.id = id
        self.clientID = clientID
        self.externalID = externalID
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.duration = duration
        self.chunkCount = chunkCount
        self.status = status
        self.transcript = transcript
        self.segments = segments
        self.createdAt = createdAt ?? startedAt
    }

    public var clockDuration: String {
        DurationFormat.clock(duration)
    }

    public var minuteSecondDuration: String {
        DurationFormat.minSec(duration)
    }

    public var chunkLabel: String {
        DurationFormat.chunkCount(chunkCount)
    }

    public var dateLabel: String {
        SessionDateFormat.date(startedAt)
    }

    public var isFinished: Bool {
        status == .saved || status == .discarded
    }
}
