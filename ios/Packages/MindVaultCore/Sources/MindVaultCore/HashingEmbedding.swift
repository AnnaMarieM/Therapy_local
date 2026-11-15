import Foundation
import MindVaultModels

public enum HashingEmbedding {
    public static let dimension = 768
    private static let offsetBasis: UInt64 = 14_695_981_039_346_656_037
    private static let prime: UInt64 = 1_099_511_628_211

    public static func embed(_ text: String) -> EmbeddingVector {
        let tokens = TextNormalization.tokens(text)
        guard !tokens.isEmpty else { return EmbeddingVector(values: Array(repeating: 0, count: dimension)) }
        var values = Array(repeating: 0.0, count: dimension)
        for index in tokens.indices {
            add(token: tokens[index], weight: 1, to: &values)
            if index > 0 {
                add(token: tokens[index - 1] + " " + tokens[index], weight: 0.5, to: &values)
            }
        }
        return EmbeddingVector(values: normalize(values))
    }

    public static func fnv1a(_ value: String) -> UInt64 {
        var hash = offsetBasis
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* prime
        }
        return hash
    }

    private static func add(token: String, weight: Double, to values: inout [Double]) {
        let hash = fnv1a(token)
        let index = Int(hash % UInt64(dimension))
        let sign: Double = (hash & (1 << 63)) == 0 ? 1 : -1
        let lengthWeight = 1 + log(1 + Double(token.count))
        values[index] += sign * weight * lengthWeight
    }

    private static func normalize(_ values: [Double]) -> [Float] {
        let magnitude = values.reduce(0) { $0 + $1 * $1 }.squareRoot()
        guard magnitude > 0 else { return values.map { _ in 0 } }
        return values.map { Float($0 / magnitude) }
    }
}
