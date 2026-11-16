/// Embedding helpers, ported from app/src/main/java/com/therapylocal/app/data/VectorMath.kt:
/// brute-force cosine and top-k. Pure Swift, unit-tested.
public enum VectorMath {
    /// 0 when either vector is all zeros. Vectors must have the same size.
    public static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        precondition(a.count == b.count, "vector sizes differ: \(a.count) vs \(b.count)")
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for i in a.indices {
            dot += Double(a[i]) * Double(b[i])
            na += Double(a[i]) * Double(a[i])
            nb += Double(b[i]) * Double(b[i])
        }
        if na == 0.0 || nb == 0.0 { return 0 }
        return Float(dot / (na.squareRoot() * nb.squareRoot()))
    }

    /// The `k` items closest to `query`, best first. Items whose vector has another
    /// size (another embedding model) are skipped.
    public static func topK<T>(_ items: [(T, [Float])], _ query: [Float], _ k: Int) -> [(T, Float)] {
        items.filter { $0.1.count == query.count }
            .map { ($0.0, cosine($0.1, query)) }
            .sorted { $0.1 > $1.1 }
            .prefix(k)
            .map { $0 }
    }
}
