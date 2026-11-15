import Foundation
import MindVaultCore

protocol SearchServing: Sendable {
    func search(query: String, clientID: UUID?) async throws -> [SearchResult]
}

struct VaultSearchService: SearchServing {
    let repository: any VaultRepository

    func search(query: String, clientID: UUID?) async throws -> [SearchResult] {
        let document = try await repository.loadDocument()
        return SearchRanking.search(
            query: query,
            chunks: document.chunks,
            facts: document.facts,
            sessions: document.sessions,
            clientID: clientID
        )
    }
}
