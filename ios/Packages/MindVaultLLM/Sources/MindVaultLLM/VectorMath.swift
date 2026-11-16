/// Embedding helpers, ported from app/src/main/java/com/therapylocal/app/data/VectorMath.kt:
/// brute-force cosine and top-k. Pure Swift, unit-tested.
///
/// Internal on purpose: MindVaultCore owns the public `VectorMath` contract
/// (`ios/CONTRACTS.md` shared-contracts rule) — and worse than the SHA case, core's
/// `cosine([Float],[Float]) -> Double` and this one's `-> Float` are the same
/// signature with different return types, so an unqualified call would not even
/// compile once both packages are imported. The Float arithmetic stays (it matches
/// the LLM's float pipeline and Android's `VectorMath.kt` returns Float); consumers
/// use core's Double form or qualify.
enum VectorMath {
    /// 0 when either vector is all zeros. Vectors must have the same size.
    /// The products are rounded to Float FIRST and then widened — Kotlin's
    /// `dot += a[i] * b[i]` widens the Float result, not the operands — so near-tied
    /// `topK` orders agree with Android instead of differing in the last bits.
    static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        precondition(a.count == b.count, "vector sizes differ: \(a.count) vs \(b.count)")
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for i in a.indices {
            dot += Double(a[i] * b[i])
            na += Double(a[i] * a[i])
            nb += Double(b[i] * b[i])
        }
        if na == 0.0 || nb == 0.0 { return 0 }
        return Float(dot / (na.squareRoot() * nb.squareRoot()))
    }

    /// The `k` items closest to `query`, best first. Items whose vector has another
    /// size (another embedding model) are skipped.
    static func topK<T>(_ items: [(T, [Float])], _ query: [Float], _ k: Int) -> [(T, Float)] {
        items.filter { $0.1.count == query.count }
            .map { ($0.0, cosine($0.1, query)) }
            .sorted { $0.1 > $1.1 }
            .prefix(k)
            .map { $0 }
    }
}
