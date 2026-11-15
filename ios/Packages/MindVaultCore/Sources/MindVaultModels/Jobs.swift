import Foundation

public enum JobKind: String, Codable, CaseIterable, Sendable {
    case transcribe
    case extractFacts
    case reviewDuplicates

    public var title: String {
        switch self {
        case .transcribe: return "Transcribe audio"
        case .extractFacts: return "Extract facts"
        case .reviewDuplicates: return "Review duplicates"
        }
    }
}

public enum JobStatus: String, Codable, CaseIterable, Sendable {
    case queued
    case running
    case done
    case failed
    case cancelled

    public var isFinished: Bool {
        self == .done || self == .failed || self == .cancelled
    }
}

public struct Job: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var kind: JobKind
    public var sessionID: UUID?
    public var chunkIndex: Int?
    public var status: JobStatus
    public var attempts: Int
    public var progress: Double?
    public var lastError: String?
    public var createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(),
        kind: JobKind,
        sessionID: UUID? = nil,
        chunkIndex: Int? = nil,
        status: JobStatus = .queued,
        attempts: Int = 0,
        progress: Double? = nil,
        lastError: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date? = nil
    ) {
        self.id = id
        self.kind = kind
        self.sessionID = sessionID
        self.chunkIndex = chunkIndex
        self.status = status
        self.attempts = attempts
        self.progress = progress
        self.lastError = lastError
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
    }
}
