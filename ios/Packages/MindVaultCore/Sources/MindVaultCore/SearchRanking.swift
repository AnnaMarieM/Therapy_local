import Foundation
import MindVaultModels

public struct Ranked<Item> {
    public let item: Item
    public let score: Double

    public init(item: Item, score: Double) {
        self.item = item
        self.score = score
    }
}

public enum SearchRanking {
    public static func rank<Item>(
        query: EmbeddingVector,
        items: [Item],
        embedding: (Item) -> EmbeddingVector?,
        limit: Int = 10,
        minimumScore: Double = -1
    ) -> [Ranked<Item>] {
        guard limit > 0 else { return [] }
        return items
            .compactMap { item -> Ranked<Item>? in
                guard let vector = embedding(item) else { return nil }
                let score = query.cosineSimilarity(to: vector)
                guard score >= minimumScore else { return nil }
                return Ranked(item: item, score: score)
            }
            .sorted { $0.score > $1.score }
            .prefix(limit)
            .map { $0 }
    }

    public static func cues(
        query: EmbeddingVector,
        facts: [Fact],
        clientID: UUID? = nil,
        limit: Int = 10,
        minimumScore: Double = 0.25
    ) -> [MemoryCue] {
        let candidates = facts.filter { fact in
            guard fact.embedding != nil else { return false }
            guard let clientID else { return true }
            return fact.clientID == clientID
        }
        return rank(query: query, items: candidates, embedding: \.embedding, limit: limit, minimumScore: minimumScore)
            .map { ranked in
                MemoryCue(
                    factID: ranked.item.id,
                    clientID: ranked.item.clientID,
                    sessionID: ranked.item.sessionID,
                    chunkID: ranked.item.chunkID,
                    category: ranked.item.category,
                    text: ranked.item.text,
                    score: ranked.score,
                    createdAt: ranked.item.createdAt
                )
            }
    }

    public static func lexicalScore(query: String, text: String) -> Double {
        let queryTokens = Set(TextNormalization.tokens(query))
        guard !queryTokens.isEmpty else { return 0 }
        let textTokens = TextNormalization.tokens(text)
        guard !textTokens.isEmpty else { return 0 }
        let hits = queryTokens.intersection(textTokens).count
        var score = Double(hits) / Double(queryTokens.count)
        let normalizedQuery = TextNormalization.normalize(query)
        if !normalizedQuery.isEmpty, TextNormalization.normalize(text).contains(normalizedQuery) {
            score += 0.5
        }
        return score
    }

    public static func rankLexical<Item>(
        query: String,
        items: [Item],
        text: (Item) -> String,
        limit: Int = 10
    ) -> [Ranked<Item>] {
        guard limit > 0 else { return [] }
        return items
            .map { Ranked(item: $0, score: lexicalScore(query: query, text: text($0))) }
            .filter { $0.score > 0 }
            .sorted { $0.score > $1.score }
            .prefix(limit)
            .map { $0 }
    }

    public static func search(
        query: String,
        chunks: [Chunk],
        facts: [Fact],
        sessions: [Session],
        clientID: UUID? = nil,
        limit: Int = 20
    ) -> [SearchResult] {
        let normalizedQuery = TextNormalization.normalize(query)
        guard !normalizedQuery.isEmpty else { return [] }
        let sessionByID = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, $0) })
        let allowedSessions: Set<UUID>? = clientID.map { id in
            Set(sessions.filter { $0.clientID == id }.map(\.id))
        }

        var results: [SearchResult] = []
        for chunk in chunks {
            guard let session = sessionByID[chunk.sessionID] else { continue }
            if let allowedSessions, !allowedSessions.contains(chunk.sessionID) { continue }
            let score = lexicalScore(query: query, text: chunk.text)
            guard score > 0 else { continue }
            results.append(
                SearchResult(
                    clientID: session.clientID,
                    sessionID: chunk.sessionID,
                    chunkID: chunk.id,
                    text: chunk.text,
                    snippet: TextFormatting.snippet(chunk.text),
                    score: score,
                    startMs: chunk.startMs,
                    endMs: chunk.endMs
                )
            )
        }
        for fact in facts {
            guard sessionByID[fact.sessionID] != nil else { continue }
            if let allowedSessions, !allowedSessions.contains(fact.sessionID) { continue }
            let score = lexicalScore(query: query, text: fact.text) * 0.9
            guard score > 0 else { continue }
            results.append(
                SearchResult(
                    clientID: fact.clientID,
                    sessionID: fact.sessionID,
                    chunkID: fact.chunkID,
                    factID: fact.id,
                    text: fact.text,
                    snippet: TextFormatting.snippet(fact.evidence),
                    score: score,
                    startMs: 0,
                    endMs: 0
                )
            )
        }
        return Array(results.sorted { $0.score > $1.score }.prefix(max(0, limit)))
    }
}
