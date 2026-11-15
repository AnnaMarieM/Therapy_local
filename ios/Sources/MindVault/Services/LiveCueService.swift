import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class LiveCueService: ObservableObject {
    @Published private(set) var cues: [MemoryCue] = []
    @Published private(set) var lastTranscript = ""

    private let embeddings: any EmbeddingProviding
    private let repository: any VaultRepository
    private var facts: [Fact] = []
    private var loadedClientID: UUID?

    init(embeddings: any EmbeddingProviding, repository: any VaultRepository) {
        self.embeddings = embeddings
        self.repository = repository
    }

    func update(partialTranscript: String, client: Client, sessionID: UUID) async {
        if loadedClientID != client.id {
            await loadFacts(clientID: client.id)
        }
        lastTranscript = partialTranscript
        guard !partialTranscript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            cues = []
            return
        }
        let query = embeddings.embed(text: partialTranscript)
        cues = SearchRanking.cues(query: query, facts: facts, clientID: client.id, limit: 3, minimumScore: 0.2)
    }

    func clear() {
        cues = []
        lastTranscript = ""
        facts = []
        loadedClientID = nil
    }

    private func loadFacts(clientID: UUID) async {
        let document = (try? await repository.loadDocument()) ?? .empty()
        facts = document.facts(for: clientID).map { fact in
            guard fact.embedding == nil else { return fact }
            var copy = fact
            copy.embedding = embeddings.embed(text: fact.text)
            return copy
        }
        loadedClientID = clientID
    }
}
