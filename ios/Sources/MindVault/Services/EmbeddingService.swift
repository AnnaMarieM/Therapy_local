import Foundation
import MindVaultCore
import NaturalLanguage

protocol EmbeddingProviding: Sendable {
    var modelID: String { get }
    var isSemantic: Bool { get }
    func embed(text: String) -> EmbeddingVector
    func embedAudio(pcm: Data) -> EmbeddingVector?
}

final class SystemEmbeddingService: EmbeddingProviding, @unchecked Sendable {
    private let sentenceEmbedding: NLEmbedding?

    init() {
        sentenceEmbedding = NLEmbedding.sentenceEmbedding(for: .english)
    }

    var isSemantic: Bool {
        sentenceEmbedding != nil
    }

    var modelID: String {
        sentenceEmbedding == nil ? "hashing-768" : "system-sentence-embedding"
    }

    func embed(text: String) -> EmbeddingVector {
        if let vector = sentenceEmbedding?.vector(for: text) {
            return EmbeddingVector(values: vector.map(Float.init))
        }
        return HashingEmbedding.embed(text)
    }

    func embedAudio(pcm: Data) -> EmbeddingVector? {
        nil
    }
}
