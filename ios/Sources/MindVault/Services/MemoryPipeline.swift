import Foundation
import MindVaultCore

struct MemoryProcessingResult: Sendable {
    let facts: [Fact]
    let mentions: [Mention]
}

protocol MemoryProcessing: Sendable {
    func process(session: Session, client: Client) async throws -> MemoryProcessingResult
}

struct MemoryPipeline: MemoryProcessing {
    let repository: any VaultRepository
    let extractor: any FactExtracting
    let logger: any AppLogger

    func process(session: Session, client: Client) async throws -> MemoryProcessingResult {
        let result = extractor.extract(
            transcript: session.transcript,
            client: client,
            sessionID: session.id,
            chunkID: nil
        )
        if !result.facts.isEmpty {
            try await repository.appendFacts(result.facts, mentions: result.mentions)
        }
        logger.log(LogEntry(level: .info, category: "memory", message: "facts_extracted count=\(result.facts.count)"))
        return MemoryProcessingResult(facts: result.facts, mentions: result.mentions)
    }
}
