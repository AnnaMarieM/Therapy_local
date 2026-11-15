import XCTest
import MindVaultCore
@testable import MindVault

final class AudioChunkAssemblerTests: XCTestCase {
    func testShortRecordingsAndBoundaries() {
        assertChunks(seconds: 0.999, expectedMs: [])
        assertChunks(seconds: 1, expectedMs: [(0, 1_000)])
        assertChunks(seconds: 19, expectedMs: [(0, 19_000)])
        assertChunks(seconds: 20, expectedMs: [(0, 20_000)])
        assertChunks(seconds: 22, expectedMs: [(0, 20_000), (15_000, 22_000)])
        assertChunks(seconds: 35, expectedMs: [(0, 20_000), (15_000, 35_000)])
        assertChunks(seconds: 37, expectedMs: [(0, 20_000), (15_000, 35_000), (30_000, 37_000)])
        assertChunks(seconds: 52, expectedMs: [(0, 20_000), (15_000, 35_000), (30_000, 50_000), (45_000, 52_000)])
    }

    func testRangesMatchChunkMath() {
        for seconds in [19.0, 20.0, 22.0, 37.0, 52.0] {
            let chunks = assemble(seconds: seconds)
            let durationMs = Int(seconds * 1_000)
            XCTAssertEqual(
                chunks.map { ChunkRange(index: $0.index, startMs: $0.startMs, endMs: $0.endMs, overlapMs: $0.overlapMs) },
                ChunkMath.ranges(durationMs: durationMs),
                "ranges differ at \(seconds)s"
            )
            XCTAssertEqual(chunks.count, ChunkMath.chunkCount(durationMs: durationMs))
        }
    }

    func testTrailingTailShorterThanTwoSecondsIsDropped() {
        assertChunks(seconds: 21, expectedMs: [(0, 20_000)])
        assertChunks(seconds: 21.9, expectedMs: [(0, 20_000)])
        assertChunks(seconds: 22, expectedMs: [(0, 20_000), (15_000, 22_000)])
        assertChunks(seconds: 36.999, expectedMs: [(0, 20_000), (15_000, 35_000)])
        assertChunks(seconds: 37, expectedMs: [(0, 20_000), (15_000, 35_000), (30_000, 37_000)])
    }

    func testStreamingInBlocksMatchesOneShot() {
        let sampleRate = ChunkMath.sampleRate
        let total = 37 * sampleRate
        let timeline = Self.timeline(samples: total)

        var oneShot = AudioChunkAssembler()
        var chunks = oneShot.append(pcm16: timeline)
        if let final = oneShot.finish() { chunks.append(final) }

        var streamed = AudioChunkAssembler()
        var streamedChunks: [FinalizedAudioChunk] = []
        var offset = 0
        let block = sampleRate
        while offset < total {
            let end = min(offset + block, total)
            streamedChunks += streamed.append(pcm16: Self.timeline(samples: end - offset, startingAt: offset))
            offset = end
        }
        if let final = streamed.finish() { streamedChunks.append(final) }

        XCTAssertEqual(chunks.map(\.startMs), streamedChunks.map(\.startMs))
        XCTAssertEqual(chunks.map(\.endMs), streamedChunks.map(\.endMs))
        XCTAssertEqual(chunks, streamedChunks)
    }

    func testOverlapIsTheSameAudioInConsecutiveChunks() {
        let chunks = assemble(seconds: 37)
        XCTAssertEqual(chunks.count, 3)
        for index in 1..<chunks.count {
            let previous = chunks[index - 1]
            let current = chunks[index]
            let overlapBytes = ChunkMath.overlapSamples * 2
            XCTAssertEqual(
                previous.pcm16.suffix(overlapBytes),
                current.pcm16.prefix(overlapBytes),
                "overlap mismatch between chunk \(index - 1) and \(index)"
            )
        }
    }

    func testPauseResumeDoesNotDuplicateOrGap() {
        let timeline = Self.timeline(samples: 22 * ChunkMath.sampleRate)
        let tenSeconds = 10 * ChunkMath.sampleRate * 2
        let nineSeconds = 9 * ChunkMath.sampleRate * 2
        let threeSeconds = 3 * ChunkMath.sampleRate * 2

        var assembler = AudioChunkAssembler()
        var chunks = assembler.append(pcm16: timeline.prefix(tenSeconds))
        chunks += assembler.append(pcm16: timeline.subdata(in: tenSeconds..<(tenSeconds + nineSeconds)))
        // A pause means no samples are appended; the timeline is sample-based, not wall-clock.
        chunks += assembler.append(
            pcm16: timeline.subdata(in: (tenSeconds + nineSeconds)..<(tenSeconds + nineSeconds + threeSeconds))
        )
        if let final = assembler.finish() { chunks.append(final) }

        XCTAssertEqual(chunks.map(\.startMs), [0, 15_000])
        XCTAssertEqual(chunks.map(\.endMs), [20_000, 22_000])
        XCTAssertEqual(assembler.totalSamples, 22 * ChunkMath.sampleRate)
        XCTAssertEqual(chunks.lazy.map(\.pcm16.count).reduce(0, +) - ChunkMath.overlapSamples * 2, timeline.count)
    }

    func testOddByteAtEndIsIgnored() {
        var assembler = AudioChunkAssembler()
        var data = Self.timeline(samples: ChunkMath.sampleRate)
        data.append(0x7F)
        let chunks = assembler.append(pcm16: data)
        XCTAssertTrue(chunks.isEmpty)
        XCTAssertEqual(assembler.totalSamples, ChunkMath.sampleRate)
    }

    func testFinishIsIdempotent() {
        var assembler = AudioChunkAssembler()
        _ = assembler.append(pcm16: Self.timeline(samples: 5 * ChunkMath.sampleRate))
        XCTAssertNotNil(assembler.finish())
        XCTAssertNil(assembler.finish())
        XCTAssertTrue(assembler.append(pcm16: Self.timeline(samples: 1_000)).isEmpty)
    }

    private func assertChunks(
        seconds: Double,
        expectedMs: [(Int, Int)],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let chunks = assemble(seconds: seconds)
        XCTAssertEqual(chunks.map(\.startMs), expectedMs.map(\.0), file: file, line: line)
        XCTAssertEqual(chunks.map(\.endMs), expectedMs.map(\.1), file: file, line: line)
        XCTAssertEqual(
            chunks.map(\.overlapMs),
            expectedMs.indices.map { $0 == 0 ? 0 : ChunkMath.overlapMs },
            file: file,
            line: line
        )
        for chunk in chunks {
            XCTAssertEqual(chunk.pcm16.count, (chunk.endMs - chunk.startMs) * ChunkMath.sampleRate / 1_000 * 2, file: file, line: line)
        }
    }

    private func assemble(seconds: Double) -> [FinalizedAudioChunk] {
        let sampleCount = Int((seconds * Double(ChunkMath.sampleRate)).rounded(.down))
        var assembler = AudioChunkAssembler()
        var chunks = assembler.append(pcm16: Self.timeline(samples: sampleCount))
        if let final = assembler.finish() { chunks.append(final) }
        return chunks
    }

    private static func timeline(samples: Int, startingAt offset: Int = 0) -> Data {
        var data = Data(capacity: samples * 2)
        for index in 0..<samples {
            let value = Int16((offset + index) % 20_000)
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        return data
    }
}
