import XCTest
@testable import MindVaultLLM

/// Brute-force cosine and top-k. Ported from VectorMath.kt's unit tests (the numbers are
/// textbook cases).
final class VectorMathTests: XCTestCase {
    func testCosineOfIdenticalVectorsIsOne() {
        XCTAssertEqual(VectorMath.cosine([1, 2, 3], [1, 2, 3]), 1.0, accuracy: 1e-6)
        XCTAssertEqual(VectorMath.cosine([0.5], [1]), 1.0, accuracy: 1e-6)
    }

    func testCosineOfOrthogonalVectorsIsZero() {
        XCTAssertEqual(VectorMath.cosine([1, 0], [0, 1]), 0.0, accuracy: 1e-6)
        XCTAssertEqual(VectorMath.cosine([1, 0], [-1, 0]), -1.0, accuracy: 1e-6)
    }

    func testCosineOfZeroVectorsIsZero() {
        XCTAssertEqual(VectorMath.cosine([0, 0], [1, 2]), 0.0, accuracy: 1e-6)
        XCTAssertEqual(VectorMath.cosine([1, 2], [0, 0]), 0.0, accuracy: 1e-6)
        XCTAssertEqual(VectorMath.cosine([0], [0]), 0.0, accuracy: 1e-6)
    }

    func testTopKOrdersBestFirstAndSkipsOtherSizes() {
        let items: [(String, [Float])] = [
            ("a", [1, 0]),       // cos with query 1.0
            ("b", [0, 1]),       // 0.0
            ("otherSize", [1]),  // skipped: another embedding model
            ("c", [0.9, 0.1]),   // ~0.994
        ]
        let query: [Float] = [1, 0]
        let top = VectorMath.topK(items, query, 3)
        XCTAssertEqual(top.map(\.0), ["a", "c", "b"])
        XCTAssertEqual(top[0].1, 1.0, accuracy: 1e-6)
        XCTAssertEqual(top[2].1, 0.0, accuracy: 1e-6)
    }

    func testTopKLimitsTheResult() {
        let items: [(Int, [Float])] = (0..<5).map { ($0, [Float($0), 1]) }
        XCTAssertEqual(VectorMath.topK(items, [1, 1], 2).count, 2)
        XCTAssertEqual(VectorMath.topK(items, [1, 1], 10).count, 5)
        let empty: [(Int, [Float])] = []
        XCTAssertEqual(VectorMath.topK(empty, [1, 1], 3).count, 0)
    }
}
