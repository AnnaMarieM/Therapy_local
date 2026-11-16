import Testing
import Foundation
@testable import MindVaultASR

// Tests for the pure logic of the package (ios/KANBAN.md: every package tests its
// pure logic on Linux). The whisper engine itself needs a model file, so the
// runtime path is covered by integration on a device; everything here is the
// deterministic port of the Android contracts.

@Suite("Pcm widening (Android AsrEngine.Pcm)")
struct PcmTests {
    @Test func wideningDividesBy32768() {
        #expect(Pcm.toFloats([0]) == [0.0])
        #expect(Pcm.toFloats([1]) == [Float(1) / 32768.0])
        #expect(Pcm.toFloats([-1]) == [Float(-1) / 32768.0])
        #expect(Pcm.toFloats([Int16.max]) == [Float(Int16.max) / 32768.0])
        #expect(Pcm.toFloats([Int16.min]) == [Float(Int16.min) / 32768.0])
    }

    @Test func seconds() {
        #expect(Pcm.seconds([Int16](repeating: 0, count: 16_000)) == 1.0)
        #expect(Pcm.seconds([]) == 0.0)
        #expect(Pcm.seconds([Int16](repeating: 0, count: 800)) == 0.05)
    }

    @Test func rmsOfSilenceAndSquare() {
        #expect(Pcm.rms([]) == 0)
        #expect(Pcm.rms([0, 0, 0]) == 0)
        // Full-scale alternating samples: RMS is the full scale, 1.0.
        let square = [Int16](repeating: 0, count: 1000).enumerated().map { i, _ in i % 2 == 0 ? Int16.max : Int16.min }
        #expect(abs(Pcm.rms(square) - 1.0) < 0.001)
        // Half amplitude (sine-free constant block): RMS = amplitude.
        #expect(abs(Pcm.rms([Int16](repeating: 16384, count: 100)) - 0.5) < 0.001)
    }

    @Test func littleEndianDecode() {
        // 0x0001 little endian = 1; 0xFFFF little endian = -1; 0x8000 = -32768.
        let data = Data([0x01, 0x00, 0xFF, 0xFF, 0x00, 0x80])
        let floats = Pcm.floatsFromLittleEndianData(data)
        #expect(floats.count == 3)
        #expect(floats[0] == Float(1) / 32768.0)
        #expect(floats[1] == Float(-1) / 32768.0)
        #expect(floats[2] == Float(-32768) / 32768.0)
    }
}

@Suite("WhisperClean (CONTRACTS cleaning, whisper side)")
struct WhisperCleanTests {
    @Test func removesBracketedMarkers() {
        #expect(WhisperClean.clean("Hello [Music] world") == "Hello world")
        #expect(WhisperClean.clean("[BLANK_AUDIO]") == "")
        #expect(WhisperClean.clean("[ Silence ] ok [inaudible] next") == "ok next")
        // Nested-looking but escaped forms still match the bracket contract.
        #expect(WhisperClean.clean("a [Applause] b") == "a b")
    }

    @Test func keepsParenthesesAndWords() {
        #expect(WhisperClean.clean("He said (quietly) hello") == "He said (quietly) hello")
        #expect(WhisperClean.clean("don't stop") == "don't stop")
    }

    @Test func collapsesWhitespace() {
        #expect(WhisperClean.clean("a\tb\nc  d") == "a b c d")
        #expect(WhisperClean.clean("  spaced   out  ") == "spaced out")
        #expect(WhisperClean.collapsed("x\n\ny") == "x y")
    }

    @Test func batchKeepsOrder() {
        #expect(WhisperClean.clean(["[Music] a", "b  [BLANK_AUDIO] c"]) == ["a", "b c"])
    }
}

@Suite("SegmentMerge stitch (Android TranscriptMerge)")
struct SegmentMergeTests {
    /// chunk i covers [i*step, i*step+chunk); a 20 s chunk with a 5 s overlap.
    private func chunk(_ words: [String], fromMs: Int64, chunkMs: Int64 = 20_000) -> [TimedSegment] {
        [TimedSegment(startMs: fromMs, endMs: fromMs + chunkMs / 4, text: words.joined(separator: " "))]
    }

    @Test func timedWordsSpreadTimeEvenly() {
        let words = SegmentMerge.timedWords(TimedSegment(startMs: 1000, endMs: 3000, text: "a b c"), segmentIndex: 2)
        #expect(words.count == 3)
        #expect(words[0].startMs == 1000 && words[0].endMs < words[1].startMs + 400)
        #expect(words[2].endMs == 3000)
        #expect(words.allSatisfy { $0.segmentIndex == 2 })
        #expect(SegmentMerge.timedWords(TimedSegment(startMs: 0, endMs: 0, text: "  "), segmentIndex: 0).isEmpty)
    }

    @Test func identicalOverlapRemovedOnce() {
        let first = chunk(["alpha", "bravo", "charlie", "delta", "echo"], fromMs: 0)
        let second = chunk(["charlie", "delta", "echo", "foxtrot", "golf"], fromMs: 15_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5000)
        let text = result.segments.map(\.text).joined(separator: " ")
        // The repeated words survive exactly once in the merged timeline.
        for w in ["charlie", "delta", "echo"] {
            #expect(text.components(separatedBy: w).count - 1 == 1)
        }
        #expect(text.contains("alpha") && text.contains("foxtrot") && text.contains("golf"))
        #expect(result.removedWords.count == 2)
        #expect(result.removedWords[0] + result.removedWords[1] >= 1)
    }

    @Test func disjointChunksKeptWhole() {
        let first = chunk(["one", "two"], fromMs: 0)
        let second = chunk(["three", "four"], fromMs: 20_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5000)
        let text = result.segments.map(\.text).joined(separator: " ")
        #expect(text == "one two three four")
        #expect(result.removedWords == [0, 0])
    }

    @Test func emptyChunkPassesThrough() {
        let first: [TimedSegment] = []
        let second = chunk(["word"], fromMs: 20_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5000)
        #expect(result.segments.map(\.text) == ["word"])
    }
}

@Suite("ModelRegistry pins (Android ModelSpec shape)")
struct ModelRegistryTests {
    @Test func hostedDefaultPinned() {
        let spec = ModelRegistry.default
        #expect(spec.id == "whisper-large-v3-turbo-q8_0")
        #expect(spec.files.count == 1)
        let file = spec.files[0]
        #expect(file.url.hasPrefix("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"))
        #expect(file.size == 874_188_075)
        #expect(file.sha256.count == 64 && file.sha256 == file.sha256.lowercased())
    }

    @Test func unhostedQualityPinMirrorsAndroid() {
        // Android keeps WHISPER_Q8_URL = "" for its own q8_0 quantisation: no URL,
        // hand-installed. The iOS port must keep the same honesty.
        let spec = ModelRegistry.whisperLargeV3Q8
        #expect(spec.files[0].url == "")
        #expect(spec.files[0].size == 1_656_538_283)
    }

    @Test func registryShape() {
        #expect(ModelRegistry.all.count >= 2)
        #expect(Set(ModelRegistry.all.map(\.id)).count == ModelRegistry.all.count)
        #expect(ModelRegistry.byId("whisper-large-v3-turbo-q8_0") != nil)
        #expect(ModelRegistry.byId("nope") == nil)
        for spec in ModelRegistry.all {
            #expect(spec.totalBytes == spec.files.reduce(0) { $0 + $1.size })
            #expect(!spec.subdir.isEmpty)
            for f in spec.files where !f.url.isEmpty {
                #expect(f.url.hasPrefix("https://"))
            }
        }
    }
}

@Suite("URLTransfer tuning (Android ResumableDownload)")
struct URLTransferTests {
    @Test func backoffDoublesAndCaps() {
        #expect(URLTransfer.backoffMs(failures: 1) == 1_000)
        #expect(URLTransfer.backoffMs(failures: 2) == 2_000)
        #expect(URLTransfer.backoffMs(failures: 3) == 4_000)
        #expect(URLTransfer.backoffMs(failures: 0) == 1_000)
        #expect(URLTransfer.backoffMs(failures: 6) == 30_000)
        #expect(URLTransfer.backoffMs(failures: 99) == 30_000)
    }

    @Test func stallWatchNeedsSlowWindow() {
        var watch = StallWatch(minBytesPerSec: 1_000, windowMs: 1_000)
        // Bytes within the window never stall.
        var early = watch.stalled(n: 100, now: 0)
        #expect(!early)
        early = watch.stalled(n: 100, now: 0.2)
        #expect(!early)
        // A full window at 100 B/s against 1000 B/s minimum: stalled.
        let slow = watch.stalled(n: 100, now: 2.0)
        #expect(slow)
        // The window reset: immediate traffic is fine again.
        let reset = watch.stalled(n: 5_000, now: 2.1)
        #expect(!reset)
    }

    @Test func stallWatchPassesWhenFast() {
        var watch = StallWatch(minBytesPerSec: 1_000, windowMs: 1_000)
        var first = watch.stalled(n: 10_000, now: 0)
        #expect(!first)
        first = watch.stalled(n: 10_000, now: 1.5)
        #expect(!first)
    }
}
