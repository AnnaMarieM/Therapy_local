import XCTest
import MindVaultCore
@testable import MindVaultPerception

final class SegmentBuilderTests: XCTestCase {
    private func chunk(_ idx: Int, _ text: String?) -> ChunkRow {
        ChunkRow(
            recordingId: 1, idx: idx, startMs: Int64(idx) * 15_000, endMs: Int64(idx) * 15_000 + 20_000,
            overlapMs: idx == 0 ? 0 : 5_000, text: text
        )
    }

    /// Overlapping chunks as a recogniser hears them: each repeats the last few words of the one
    /// before, with a mishearing at the seam.
    private var chunks: [ChunkRow] {
        [
            chunk(0, "we moved to the coast in may and my sister ann stayed behind in leeds"),
            chunk(1, "ann stayed behind in leads she says she loves the new flat but misses us"),
            chunk(2, "but misses us a lot and calls every sunday evening after dinner"),
        ]
    }

    func testSegmentsReadInOrderAreTheMergedTranscript() {
        let segments = SegmentBuilder.build(chunks)
        let merged = TranscriptMerge.merge(chunks.map { $0.text ?? "" })
        XCTAssertEqual(merged.text, segments.map(\.text).joined(separator: " "))
        XCTAssertEqual([1, 2, 3], segments.map(\.idx))
        // Every seam found its overlap, so no segment repeats the words the chunk before it already has.
        XCTAssertTrue(merged.removedWords.dropFirst().allSatisfy { $0 > 0 })
        XCTAssertEqual(1, segments.map(\.text).joined(separator: " ").components(separatedBy: "ann stayed behind").count - 1)
    }

    func testTimesStayInsideTheirChunkAndNarrowToTheKeptWords() {
        let segments = SegmentBuilder.build(chunks)
        for (i, s) in segments.enumerated() {
            let c = chunks[i]
            XCTAssertTrue(s.startMs >= c.startMs && s.endMs <= c.endMs && s.startMs < s.endMs, "segment \(i): \(s)")
        }
        // The first chunk keeps its start; a later one drops its repeated opening words, so its
        // segment starts after the chunk does.
        XCTAssertEqual(chunks[0].startMs, segments[0].startMs)
        XCTAssertTrue(segments[1].startMs > chunks[1].startMs)
        XCTAssertEqual(chunks[2].endMs, segments[2].endMs)
    }

    func testRebuildingGivesTheSameSegments() {
        XCTAssertEqual(SegmentBuilder.build(chunks), SegmentBuilder.build(chunks.map { $0 }))
    }

    func testChunksWithoutWordsGiveNoSegmentAndIndicesStayContiguous() {
        let segments = SegmentBuilder.build([
            chunk(0, nil), ChunkRow(recordingId: 1, idx: 1, startMs: 15_000, endMs: 35_000, overlapMs: 5_000, text: chunks[0].text),
            chunk(2, "   "), chunk(3, "a completely different topic now"),
        ])
        XCTAssertEqual([1, 2], segments.map(\.idx))
        XCTAssertEqual("a completely different topic now", segments[1].text)
        XCTAssertEqual([], SegmentBuilder.build([]))
        XCTAssertEqual([], SegmentBuilder.build([chunk(0, nil), chunk(1, "")]))
    }

    func testAnEchoedPromptIsNotPartOfTheTranscript() {
        let segments = SegmentBuilder.build([
            chunk(0, "my sister lives in leeds Transcribe the speech in this audio verbatim."),
        ])
        XCTAssertEqual("my sister lives in leeds", segments.only?.text)
    }

    /// The kept ranges are what segments are built on: each chunk keeps one contiguous run of its
    /// words, and in order they are the text.
    func testKeptRangesOfRandomOverlappingChunksRebuildTheMergedText() {
        var rng = SeededGenerator(seed: 77)
        let vocab = ["we", "went", "to", "the", "sea", "and", "my", "sister", "said", "it", "was", "cold", "but", "fine", "really"]
        for _ in 0..<400 {
            let story = (0..<40 + Int.random(in: 0..<80, using: &rng))
                .map { _ in vocab[Int.random(in: 0..<vocab.count, using: &rng)] + String(Int.random(in: 0..<3, using: &rng)) }
            var texts = [String]()
            var start = 0
            while start < story.count {
                let end = min(story.count, start + 10 + Int.random(in: 0..<15, using: &rng))
                texts.append(story[start..<end].joined(separator: " "))
                if end == story.count { break }
                start = end - Int.random(in: 0..<6, using: &rng) // 0-5 words of overlap
            }
            let r = TranscriptMerge.merge(texts)
            let rebuilt = texts.indices.flatMap { i -> [String] in
                let w = TranscriptMerge.words(texts[i])
                guard let kept = r.kept[i] else { return [] }
                return Array(w[kept.lowerBound...kept.upperBound])
            }
            XCTAssertEqual(r.text, rebuilt.joined(separator: " "))
            for i in texts.indices {
                guard let kept = r.kept[i] else { continue }
                XCTAssertTrue(kept.upperBound < TranscriptMerge.words(texts[i]).count)
            }
        }
    }
}
