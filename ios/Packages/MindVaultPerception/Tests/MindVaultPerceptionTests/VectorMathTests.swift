import XCTest
@testable import MindVaultPerception

final class VectorMathTests: XCTestCase {
    func testBlobIsLittleEndianFloat32() {
        // 1.0f is 0x3F800000, so little-endian bytes 00 00 80 3F.
        XCTAssertEqual([0, 0, 0x80, 0x3F], VectorMath.toBlob([1.0]))
        XCTAssertEqual(12, VectorMath.toBlob([Float](repeating: 0, count: 3)).count)
    }

    func testBlobRoundTripKeepsEveryValue() {
        let v: [Float] = [0, -0.5, 1e-30, 3.4e38, Float.leastNonzeroMagnitude, -123.456]
        XCTAssertEqual(v, VectorMath.fromBlob(VectorMath.toBlob(v)))
        XCTAssertEqual(0, VectorMath.fromBlob([]).count)
    }

    func testCosineIgnoresLengthAndKeepsSign() throws {
        XCTAssertEqual(1.0, try VectorMath.cosine([1, 2], [3, 6]), accuracy: 1e-6)
        XCTAssertEqual(-1.0, try VectorMath.cosine([1, 2], [-2, -4]), accuracy: 1e-6)
        XCTAssertEqual(0.0, try VectorMath.cosine([1, 0], [0, 5]), accuracy: 1e-6)
        // 45 degrees
        XCTAssertEqual(0.70710677, try VectorMath.cosine([1, 0], [1, 1]), accuracy: 1e-6)
    }

    func testCosineOfZeroVectorIsZeroNotNaN() throws {
        XCTAssertEqual(0.0, try VectorMath.cosine([0, 0], [1, 1]), accuracy: 0)
    }

    func testCosineRejectsDifferentSizes() {
        XCTAssertThrowsError(try VectorMath.cosine([1], [1, 0]))
    }

    func testTopKReturnsClosestFirstAndAtMostK() throws {
        let query: [Float] = [1, 0]
        let items: [(String, [Float])] = [
            ("far", [-1, 0]),
            ("near", [1, 0.1]),
            ("mid", [1, 1]),
            ("exact", [2, 0]),
        ]
        XCTAssertEqual(["exact", "near", "mid"], VectorMath.topK(items, query: query, k: 3).map(\.0))
        XCTAssertEqual(
            ["exact", "near", "mid", "far"],
            VectorMath.topK(items, query: query, k: Int.max).map(\.0)
        )
        let scores = VectorMath.topK(items, query: query, k: 4).map(\.1)
        XCTAssertEqual(1.0, try XCTUnwrap(scores.first), accuracy: 1e-6)
        XCTAssertEqual(-1.0, try XCTUnwrap(scores.last), accuracy: 1e-6)
    }

    func testTopKSkipsVectorsOfAnotherModel() {
        let items: [(String, [Float])] = [("other", [1, 0, 0]), ("same", [0, 1])]
        XCTAssertEqual(["same"], VectorMath.topK(items, query: [1, 0], k: 5).map(\.0))
        let empty: [(String, [Float])] = []
        XCTAssertTrue(VectorMath.topK(empty, query: [1], k: 5).isEmpty)
    }
}
