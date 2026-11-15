import XCTest
@testable import MindVaultPerception

final class ChunkAudioTests: XCTestCase {
    /// Chunk 0 covers 0..20 s, chunk 1 15..30 s (5 s overlap); the sample of second s has the value s.
    private func chunk(_ idx: Int) -> AudioChunk {
        let (start, end, overlap) = idx == 0 ? (Int64(0), Int64(20_000), Int64(0)) : (Int64(15_000), Int64(30_000), Int64(5_000))
        return AudioChunk(
            idx: idx, startMs: start, endMs: end, overlapMs: overlap,
            pcm: (0..<Int((end - start) * 16)).map { Int16((start * 16 + Int64($0)) / 16_000) }
        )
    }

    private var loads: [Int] = []
    private var audio: ChunkAudio!

    override func setUp() {
        super.setUp()
        loads = []
        let rows = [
            ChunkRow(recordingId: 1, idx: 0, startMs: 0, endMs: 20_000, overlapMs: 0, text: nil),
            ChunkRow(recordingId: 1, idx: 1, startMs: 15_000, endMs: 30_000, overlapMs: 5_000, text: nil),
        ]
        audio = ChunkAudio(rows: rows) { [self] idx in
            loads.append(idx)
            return chunk(idx)
        }
    }

    func testTheWholeRecordingIsOneTimelineWithoutTheRepeatedOverlap() throws {
        let all = try audio.slice(startMs: 0, endMs: audio.durationMs)
        XCTAssertEqual(30 * 16_000, all.count)
        XCTAssertTrue(all.indices.allSatisfy { Int(all[$0]) == $0 / 16_000 })
    }

    func testASliceAcrossTheChunkBoundaryIsContinuous() throws {
        let s = try audio.slice(startMs: 18_000, endMs: 22_000)
        XCTAssertEqual(4 * 16_000, s.count)
        XCTAssertEqual([18, 19, 20, 21], [Int(s[0]), Int(s[16_000]), Int(s[32_000]), Int(s[48_000])])
    }

    func testASliceInsideOneChunkLoadsOnlyThatChunk() throws {
        _ = try audio.slice(startMs: 2_000, endMs: 4_000)
        XCTAssertEqual([0], loads)
    }

    func testAStretchNoChunkCoversIsSilence() throws {
        let gap = ChunkAudio(
            rows: [ChunkRow(recordingId: 1, idx: 0, startMs: 1_000, endMs: 2_000, overlapMs: 0, text: nil)]
        ) { _ in AudioChunk(idx: 0, startMs: 1_000, endMs: 2_000, overlapMs: 0, pcm: [Int16](repeating: 7, count: 16_000)) }
        let s = try gap.slice(startMs: 0, endMs: 3_000)
        XCTAssertEqual(0, Int(s[0]))
        XCTAssertEqual(7, Int(s[20_000]))
        XCTAssertEqual(0, Int(s[40_000]))
    }
}
