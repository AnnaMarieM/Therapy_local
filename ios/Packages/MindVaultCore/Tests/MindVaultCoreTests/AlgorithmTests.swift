import XCTest
@testable import MindVaultCore

final class ChunkMathTests: XCTestCase {
    func testChunkCountVectors() {
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 0), 0)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 999), 0)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 1_000), 1)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 20_000), 1)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 21_000), 1)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 22_000), 2)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 35_000), 2)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 37_000), 3)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 40_000), 3)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 42_000), 3)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 50_000), 3)
        XCTAssertEqual(ChunkMath.chunkCount(durationMs: 52_000), 4)
    }

    func testChunkRanges() {
        let ranges = ChunkMath.ranges(durationMs: 37_000)
        XCTAssertEqual(ranges.count, 3)
        XCTAssertEqual(ranges.map(\.startMs), [0, 15_000, 30_000])
        XCTAssertEqual(ranges.map(\.endMs), [20_000, 35_000, 37_000])
        XCTAssertEqual(ranges.map(\.overlapMs), [0, 5_000, 5_000])
    }
}

final class TranscriptCleanTests: XCTestCase {
    func testStripsPromptEcho() {
        let raw = "Hello there. Transcribe the speech in this audio verbatim. Output only the transcript, nothing else. How are you?"
        let cleaned = TranscriptClean.stripPromptEcho(raw)
        XCTAssertFalse(cleaned.lowercased().contains("transcribe the speech"))
        XCTAssertFalse(cleaned.lowercased().contains("output only the transcript"))
        XCTAssertTrue(cleaned.hasPrefix("Hello there."))
    }
}

final class TranscriptMergeTests: XCTestCase {
    func testMergesOverlappingText() {
        let result = TranscriptMerge.merge([
            "one two three four five six",
            "four five six seven eight nine"
        ])
        XCTAssertTrue(result.text.contains("seven eight nine"))
        XCTAssertFalse(result.text.contains("four five six four five six"))
        XCTAssertEqual(result.removedWords.count, 2)
    }

    func testJoinsWithoutOverlap() {
        let result = TranscriptMerge.merge(["hello world", "a different sentence"])
        XCTAssertEqual(result.text, "hello world a different sentence")
        XCTAssertEqual(result.removedWords, [0, 0])
    }
}

final class TranscriptWindowsTests: XCTestCase {
    func testWindowsRespectOverlap() {
        let segments = [
            TranscriptSegment(chunkIndex: 0, startMs: 0, endMs: 1_000, text: "one two three four"),
            TranscriptSegment(chunkIndex: 1, startMs: 1_000, endMs: 2_000, text: "five six seven")
        ]
        let spans = TranscriptWindows.bySegments(segments: segments, windowWords: 5, overlapWords: 2)
        XCTAssertEqual(spans.count, 2)
        XCTAssertEqual(spans[0].text, "one two three four five")
        XCTAssertEqual(spans[1].text, "four five six seven")
        XCTAssertEqual(spans[1].segFrom, 0)
        XCTAssertEqual(spans[1].segTo, 1)
    }

    func testTokensPerWord() {
        XCTAssertEqual(TranscriptWindows.wordsFor(tokens: 1_000), 714)
        XCTAssertEqual(TranscriptWindows.wordsFor(tokens: 0), 0)
    }

    func testStripsSpeakerLabels() {
        let text = "Therapist: hello\n**Patient:** goodbye\nSpeaker 2: later"
        let stripped = TranscriptWindows.stripSpeakerLabels(text)
        XCTAssertTrue(stripped.contains("hello"))
        XCTAssertTrue(stripped.contains("goodbye"))
        XCTAssertTrue(stripped.contains("later"))
        XCTAssertFalse(stripped.contains("Therapist:"))
    }
}
