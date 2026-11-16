import Foundation

/// Embedding helpers: the little-endian float BLOB the database stores, brute-force top-k by
/// cosine, and the cosine itself. Ported exactly from `com.therapylocal.app.data.VectorMath`
/// (pure Kotlin, unit-tested on the JVM); the JVM test is mirrored in VectorMathTests.
/// No quantized byte math exists on Android, so none is ported.
public enum VectorMath {
    public enum VectorMathError: Error, Equatable {
        /// Android throws IllegalArgumentException: "vector sizes differ".
        case vectorSizesDiffer(left: Int, right: Int)
    }

    /// The little-endian float32 bytes the store keeps for a vector.
    public static func toBlob(_ v: [Float]) -> [UInt8] {
        var bytes = [UInt8]()
        bytes.reserveCapacity(v.count * 4)
        for value in v {
            bytes.append(contentsOf: floatBytes(value))
        }
        return bytes
    }

    /// Reads a [toBlob] payload. A length that is not a multiple of four is truncated, as on Android.
    public static func fromBlob(_ bytes: [UInt8]) -> [Float] {
        var out = [Float]()
        out.reserveCapacity(bytes.count / 4)
        for offset in stride(from: 0, to: bytes.count - bytes.count % 4, by: 4) {
            let bits = UInt32(bytes[offset]) | (UInt32(bytes[offset + 1]) << 8)
                | (UInt32(bytes[offset + 2]) << 16) | (UInt32(bytes[offset + 3]) << 24)
            out.append(Float(bitPattern: bits))
        }
        return out
    }

    /// 0 when either vector is all zeros. Throws when the sizes differ.
    public static func cosine(_ a: [Float], _ b: [Float]) throws -> Float {
        guard a.count == b.count else { throw VectorMathError.vectorSizesDiffer(left: a.count, right: b.count) }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for i in a.indices {
            // Kotlin accumulates each Float product widened to Double
            // (`dot += a[i] * b[i]`): the multiply rounds in Float first.
            // Widening first and multiplying in Double drifts by 1 ULP on
            // ~42% of random 768-d pairs (measured vs the JVM).
            dot += Double(a[i] * b[i])
            na += Double(a[i] * a[i])
            nb += Double(b[i] * b[i])
        }
        if na == 0.0 || nb == 0.0 { return 0 }
        return Float(dot / (na.squareRoot() * nb.squareRoot()))
    }

    /// The [k] items closest to [query], best first. Items whose vector has another size (another
    /// embedding model) are skipped. Ties keep the original order, as Kotlin's stable sort does.
    public static func topK<T>(_ items: [(T, [Float])], query: [Float], k: Int) -> [(T, Float)] {
        items
            .filter { $0.1.count == query.count }
            .enumerated()
            .map { offset, item -> (T, Float, Int) in
                // The sizes were filtered to match, so the cosine cannot throw here.
                (item.0, (try? cosine(item.1, query)) ?? 0, offset)
            }
            .sorted { a, b in
                if a.1 != b.1 { return a.1 > b.1 }
                return a.2 < b.2
            }
            .prefix(max(0, k))
            .map { ($0.0, $0.1) }
    }

    private static func floatBytes(_ value: Float) -> [UInt8] {
        let bits = value.bitPattern
        return [UInt8(bits & 0xFF), UInt8((bits >> 8) & 0xFF), UInt8((bits >> 16) & 0xFF), UInt8((bits >> 24) & 0xFF)]
    }
}
