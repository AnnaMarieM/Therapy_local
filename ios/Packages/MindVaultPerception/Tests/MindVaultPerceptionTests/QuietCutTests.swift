import XCTest
@testable import MindVaultPerception

final class QuietCutTests: XCTestCase {
    private func loud(seconds: Int) -> [Int16] {
        [Int16](repeating: 0, count: seconds * 16_000).indices.map { $0 % 2 == 0 ? Int16(8_000) : Int16(-8_000) }
    }

    func testTheCutFallsInTheQuietestWindow() {
        var pcm = loud(seconds: 10)
        // A pause of 100 ms at 5.3 s.
        for i in 5_300 * 16 ..< 5_400 * 16 {
            pcm[i] = 0
        }
        let at = QuietCut.at(pcm, startMs: 4_500, endMs: 6_500)
        XCTAssertTrue((5_325...5_375).contains(at), "at = \(at)")
    }

    func testSilenceEverywhereCutsInTheMiddle() {
        let at = QuietCut.at([Int16](repeating: 0, count: 10 * 16_000), startMs: 4_000, endMs: 6_000)
        XCTAssertTrue((4_990...5_010).contains(at), "at = \(at)")
    }

    func testARangeShorterThanTheWindowIsCutInTheMiddle() {
        XCTAssertEqual(1_010, QuietCut.at(loud(seconds: 3), startMs: 1_000, endMs: 1_020))
    }
}
