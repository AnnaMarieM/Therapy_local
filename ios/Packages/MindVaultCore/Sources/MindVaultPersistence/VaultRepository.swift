import Foundation
import MindVaultModels

public protocol VaultRepository: Sendable {
    func loadDocument() async throws -> VaultDocument
    func saveDocument(_ document: VaultDocument) async throws
    func upsertClient(_ client: Client) async throws
    func deleteClient(id: UUID) async throws
    func upsertSession(_ session: Session) async throws
    func deleteSession(id: UUID) async throws
    func appendFacts(_ facts: [Fact], mentions: [Mention]) async throws
    func replaceJobs(_ jobs: [Job]) async throws
    func updateSettings(_ settings: AppSettings) async throws
}

extension VaultDocument {
    mutating func upsertClient(_ client: Client, now: Date) {
        if let index = clients.firstIndex(where: { $0.id == client.id }) {
            var updated = client
            updated.updatedAt = now
            clients[index] = updated
        } else {
            clients.append(client)
        }
        savedAt = now
    }

    mutating func deleteClient(id: UUID, now: Date) {
        let sessionIDs = Set(sessions.filter { $0.clientID == id }.map(\.id))
        let factIDs = Set(facts.filter { $0.clientID == id }.map(\.id))
        clients.removeAll { $0.id == id }
        sessions.removeAll { $0.clientID == id }
        chunks.removeAll { sessionIDs.contains($0.sessionID) }
        facts.removeAll { $0.clientID == id }
        mentions.removeAll { factIDs.contains($0.factID) }
        jobs.removeAll { job in
            guard let sessionID = job.sessionID else { return false }
            return sessionIDs.contains(sessionID)
        }
        savedAt = now
    }

    mutating func upsertSession(_ session: Session, now: Date) {
        if let index = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[index] = session
        } else {
            sessions.append(session)
        }
        if let index = clients.firstIndex(where: { $0.id == session.clientID }) {
            clients[index].updatedAt = now
        }
        savedAt = now
    }

    mutating func deleteSession(id: UUID, now: Date) {
        let factIDs = Set(facts.filter { $0.sessionID == id }.map(\.id))
        sessions.removeAll { $0.id == id }
        chunks.removeAll { $0.sessionID == id }
        facts.removeAll { $0.sessionID == id }
        mentions.removeAll { $0.sessionID == id || factIDs.contains($0.factID) }
        jobs.removeAll { $0.sessionID == id }
        savedAt = now
    }

    mutating func appendFacts(_ newFacts: [Fact], mentions newMentions: [Mention], now: Date) {
        facts.append(contentsOf: newFacts)
        mentions.append(contentsOf: newMentions)
        savedAt = now
    }

    mutating func replaceJobs(_ newJobs: [Job], now: Date) {
        jobs = newJobs
        savedAt = now
    }

    mutating func updateSettings(_ newSettings: AppSettings, now: Date) {
        settings = newSettings
        savedAt = now
    }
}

public actor EncryptedVaultRepository: VaultRepository {
    private let store: EncryptedDocumentStore<VaultDocument>
    private let clock: @Sendable () -> Date

    public init(store: EncryptedDocumentStore<VaultDocument>, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.clock = clock
    }

    public func loadDocument() async throws -> VaultDocument {
        if let document = try await store.load() {
            return document
        }
        return VaultDocument.empty(savedAt: clock())
    }

    public func saveDocument(_ document: VaultDocument) async throws {
        var copy = document
        copy.savedAt = clock()
        try await store.save(copy)
    }

    public func upsertClient(_ client: Client) async throws {
        var document = try await loadDocument()
        document.upsertClient(client, now: clock())
        try await store.save(document)
    }

    public func deleteClient(id: UUID) async throws {
        var document = try await loadDocument()
        document.deleteClient(id: id, now: clock())
        try await store.save(document)
    }

    public func upsertSession(_ session: Session) async throws {
        var document = try await loadDocument()
        document.upsertSession(session, now: clock())
        try await store.save(document)
    }

    public func deleteSession(id: UUID) async throws {
        var document = try await loadDocument()
        document.deleteSession(id: id, now: clock())
        try await store.save(document)
    }

    public func appendFacts(_ facts: [Fact], mentions: [Mention]) async throws {
        var document = try await loadDocument()
        document.appendFacts(facts, mentions: mentions, now: clock())
        try await store.save(document)
    }

    public func replaceJobs(_ jobs: [Job]) async throws {
        var document = try await loadDocument()
        document.replaceJobs(jobs, now: clock())
        try await store.save(document)
    }

    public func updateSettings(_ settings: AppSettings) async throws {
        var document = try await loadDocument()
        document.updateSettings(settings, now: clock())
        try await store.save(document)
    }
}

public actor InMemoryVaultRepository: VaultRepository {
    private var document: VaultDocument
    private let clock: @Sendable () -> Date

    public init(document: VaultDocument = .empty(), clock: @escaping @Sendable () -> Date = { Date() }) {
        self.document = document
        self.clock = clock
    }

    public func loadDocument() async throws -> VaultDocument {
        document
    }

    public func saveDocument(_ document: VaultDocument) async throws {
        var copy = document
        copy.savedAt = clock()
        self.document = copy
    }

    public func upsertClient(_ client: Client) async {
        document.upsertClient(client, now: clock())
    }

    public func deleteClient(id: UUID) async {
        document.deleteClient(id: id, now: clock())
    }

    public func upsertSession(_ session: Session) async {
        document.upsertSession(session, now: clock())
    }

    public func deleteSession(id: UUID) async {
        document.deleteSession(id: id, now: clock())
    }

    public func appendFacts(_ facts: [Fact], mentions: [Mention]) async {
        document.appendFacts(facts, mentions: mentions, now: clock())
    }

    public func replaceJobs(_ jobs: [Job]) async {
        document.replaceJobs(jobs, now: clock())
    }

    public func updateSettings(_ settings: AppSettings) async {
        document.updateSettings(settings, now: clock())
    }
}
