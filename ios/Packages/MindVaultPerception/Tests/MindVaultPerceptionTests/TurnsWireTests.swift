import XCTest
@testable import MindVaultPerception

final class TurnsWireTests: XCTestCase {
    func testTurnsRoundTrip() throws {
        let turns = [
            DiarTurn(startMs: 0, endMs: 1_500, speaker: 0),
            DiarTurn(startMs: 1_500, endMs: 3_600_000, speaker: 1),
            DiarTurn(startMs: 3_600_000, endMs: 3_600_001, speaker: 12),
        ]
        XCTAssertEqual(turns, try TurnsWire.unpack(TurnsWire.pack(turns)))
        XCTAssertEqual([], try TurnsWire.unpack(TurnsWire.pack([])))
    }

    /// The wire is big-endian: 1_500 ms is 0x000005DC (java.nio.ByteBuffer default).
    func testBytesAreBigEndian() throws {
        let bytes = try TurnsWire.pack([DiarTurn(startMs: 0, endMs: 1_500, speaker: 0)])
        XCTAssertEqual([0, 0, 0, 0, 0, 0, 0x05, 0xDC, 0, 0, 0, 0], bytes)
    }

    func testAnHourOfBusyTalkStaysInline() throws {
        // 10k turns in 60 minutes would be a turn every 0.36 s; real sessions have a few hundred.
        let bytes = try TurnsWire.pack((0..<10_000).map { DiarTurn(startMs: Int64($0) * 360, endMs: Int64($0) * 360 + 300, speaker: Int32($0 % 2)) })
        XCTAssertEqual(120_000, bytes.count)
        XCTAssertLessThanOrEqual(bytes.count, 128 * 1024)
    }

    func testACutOffPayloadIsRefused() {
        XCTAssertThrowsError(try TurnsWire.unpack([UInt8](repeating: 0, count: 13)))
    }

    func testATimeThatDoesNotFitAnIntIsRefused() {
        XCTAssertThrowsError(try TurnsWire.pack([DiarTurn(startMs: 0, endMs: Int64(Int32.max) + 1, speaker: 0)]))
    }
}
