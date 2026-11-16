import Foundation

/// What search, cues and fact storage need from an embedder. Ported from the app contract
/// `EmbeddingProviding` (ios/Sources/MindVault/Services/EmbeddingService.swift) and the Android
/// `CueEmbedder` seam: the live-cue loop, search and the pipeline run against this protocol, so a
/// fake or a model can sit behind it.
public protocol TextEmbedding: Sendable {
    /// The model the vectors belong to; vectors are only compared within one model id
    /// ([EmbeddingSpec.modelID] for EmbeddingGemma).
    var modelID: String { get }

    /// False for the hashing fallback: its vectors are not semantic, and the app must say so
    /// rather than pretend search quality it does not have (CONTRACTS.md, capability gaps visible).
    var isSemantic: Bool { get }

    /// The vector of [text], normalized so cosine == dot product.
    func embed(text: String, query: Bool) async throws -> [Float]

    /// The vector of whole-session AUDIO (Android embeds the audio of a chunk directly, no
    /// transcript involved). Nil where no audio path exists yet — the iOS baseline keeps it nil,
    /// as the app contract allows ("embedAudio may return nil in the baseline").
    func embedAudio(pcm: [Int16]) async throws -> [Float]?
}

extension TextEmbedding {
    /// Document embeddings ([EmbeddingSpec.docPrefix]).
    public func embed(text: String) async throws -> [Float] {
        try await embed(text: text, query: false)
    }
}

/// The deterministic fallback when no semantic model is available: an FNV-1a hashed bag of words
/// and bigrams in 768 dimensions, dimension-compatible with EmbeddingGemma so stores keep working.
/// The same contract MindVaultCore's HashingEmbedding gives the app; kept here so the perception
/// seams are self-contained on every platform. Never claimed as semantic ([TextEmbedding.isSemantic]).
public struct HashingTextEmbedder: TextEmbedding {
    public let modelID = "hashing-768"
    public let isSemantic = false
    public let dimension = 768

    public init() {}

    public func embed(text: String, query: Bool) async throws -> [Float] {
        // Query and document prefix are meaningless to a hash: the same text maps to the same vector.
        _ = query
        return hashingEmbed(text)
    }

    public func embedAudio(pcm: [Int16]) async throws -> [Float]? {
        nil
    }

    private func hashingEmbed(_ text: String) -> [Float] {
        let tokens = tokenize(text)
        guard !tokens.isEmpty else { return [Float](repeating: 0, count: dimension) }
        var values = [Double](repeating: 0, count: dimension)
        for index in tokens.indices {
            add(token: tokens[index], weight: 1, to: &values)
            if index > 0 {
                add(token: tokens[index - 1] + " " + tokens[index], weight: 0.5, to: &values)
            }
        }
        let magnitude = values.reduce(0) { $0 + $1 * $1 }.squareRoot()
        guard magnitude > 0 else { return [Float](repeating: 0, count: dimension) }
        return values.map { Float($0 / magnitude) }
    }

    /// FNV-1a, the same constants MindVaultCore.HashingEmbedding uses.
    private func add(token: String, weight: Double, to values: inout [Double]) {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in token.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 1_099_511_628_211
        }
        let index = Int(hash % UInt64(dimension))
        let sign: Double = (hash & (1 << 63)) == 0 ? 1 : -1
        let lengthWeight = 1 + log(1 + Double(token.count))
        values[index] += sign * weight * lengthWeight
    }

    private func tokenize(_ text: String) -> [String] {
        text.lowercased()
            .unicodeScalars
            .map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
            .reduce(into: "") { $0.append($1) }
            .split(separator: " ")
            .map(String.init)
    }
}
