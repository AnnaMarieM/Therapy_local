import Foundation
import MindVaultModels

public enum VectorMath {
    public static func cosine(_ lhs: [Float], _ rhs: [Float]) -> Double {
        guard lhs.count == rhs.count, !lhs.isEmpty else { return 0 }
        var dot = 0.0
        var leftMagnitude = 0.0
        var rightMagnitude = 0.0
        for index in lhs.indices {
            let left = Double(lhs[index])
            let right = Double(rhs[index])
            dot += left * right
            leftMagnitude += left * left
            rightMagnitude += right * right
        }
        let denominator = leftMagnitude.squareRoot() * rightMagnitude.squareRoot()
        guard denominator > 0 else { return 0 }
        return dot / denominator
    }

    public static func cosine(_ lhs: EmbeddingVector, _ rhs: EmbeddingVector) -> Double {
        lhs.cosineSimilarity(to: rhs)
    }

    public static func normalize(_ values: [Float]) -> [Float] {
        let magnitude = values.reduce(0.0) { $0 + Double($1) * Double($1) }.squareRoot()
        guard magnitude > 0 else { return values }
        return values.map { Float(Double($0) / magnitude) }
    }

    public static func mean(_ vectors: [[Float]]) -> [Float] {
        let valid = vectors.filter { !$0.isEmpty }
        guard let first = valid.first else { return [] }
        let dimension = first.count
        var sums = Array(repeating: 0.0, count: dimension)
        var count = 0
        for vector in valid where vector.count == dimension {
            for index in 0..<dimension {
                sums[index] += Double(vector[index])
            }
            count += 1
        }
        guard count > 0 else { return [] }
        return sums.map { Float($0 / Double(count)) }
    }

    public static func topIndices(scores: [Double], limit: Int) -> [Int] {
        guard limit > 0 else { return [] }
        return scores.indices
            .sorted { lhs, rhs in
                scores[lhs] == scores[rhs] ? lhs < rhs : scores[lhs] > scores[rhs]
            }
            .prefix(limit)
            .map { $0 }
    }
}
