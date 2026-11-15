import Foundation

public struct VaultDocument: Codable, Hashable, Sendable {
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    public var clients: [Client]
    public var sessions: [Session]
    public var chunks: [Chunk]
    public var facts: [Fact]
    public var mentions: [Mention]
    public var jobs: [Job]
    public var settings: AppSettings
    public var savedAt: Date

    public init(
        schemaVersion: Int = VaultDocument.currentSchemaVersion,
        clients: [Client] = [],
        sessions: [Session] = [],
        chunks: [Chunk] = [],
        facts: [Fact] = [],
        mentions: [Mention] = [],
        jobs: [Job] = [],
        settings: AppSettings = .defaults,
        savedAt: Date = Date()
    ) {
        self.schemaVersion = schemaVersion
        self.clients = clients
        self.sessions = sessions
        self.chunks = chunks
        self.facts = facts
        self.mentions = mentions
        self.jobs = jobs
        self.settings = settings
        self.savedAt = savedAt
    }

    public static func empty(savedAt: Date = Date()) -> VaultDocument {
        VaultDocument(savedAt: savedAt)
    }

    public func client(id: UUID) -> Client? {
        clients.first { $0.id == id }
    }

    public func sessions(for clientID: UUID) -> [Session] {
        sessions.filter { $0.clientID == clientID }.sorted { $0.startedAt > $1.startedAt }
    }

    public func facts(for clientID: UUID) -> [Fact] {
        facts.filter { $0.clientID == clientID }.sorted { $0.createdAt > $1.createdAt }
    }

    public func chunks(for sessionID: UUID) -> [Chunk] {
        chunks.filter { $0.sessionID == sessionID }.sorted { $0.index < $1.index }
    }
}

public struct VaultEnvelope: Codable, Hashable, Sendable {
    public static let currentSchemaVersion = 1
    public static let algorithm = "aes-256-gcm"

    public var schemaVersion: Int
    public var algorithm: String
    public var payload: Data
    public var createdAt: Date

    public init(
        schemaVersion: Int = VaultEnvelope.currentSchemaVersion,
        algorithm: String = VaultEnvelope.algorithm,
        payload: Data,
        createdAt: Date = Date()
    ) {
        self.schemaVersion = schemaVersion
        self.algorithm = algorithm
        self.payload = payload
        self.createdAt = createdAt
    }
}
