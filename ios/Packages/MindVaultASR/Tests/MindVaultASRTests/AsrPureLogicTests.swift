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

    /// The decode is little endian by construction, not by host byte order:
    /// 0x4000 in LE is +16384 (0.5), the same byte pair read big endian would be
    /// +64. A trailing odd byte is not a sample and is dropped.
    @Test func decodeIsExplicitlyLittleEndian() {
        #expect(Pcm.floatsFromLittleEndianData(Data([0x00, 0x40])) == [0.5])
        #expect(Pcm.floatsFromLittleEndianData(Data([0x40, 0x00])) == [Float(64) / 32768.0])
        #expect(Pcm.floatsFromLittleEndianData(Data([0xFF, 0x7F])) == [Float(32767) / 32768.0])
        #expect(Pcm.floatsFromLittleEndianData(Data()) == [])
        #expect(Pcm.floatsFromLittleEndianData(Data([0x00, 0x40, 0x7F])) == [0.5])
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

    // MARK: - Review regression (SegmentMerge blockers)

    /// Deterministic SplitMix64, so the randomized cases are stable across runs.
    private struct SeededRNG: RandomNumberGenerator {
        var state: UInt64
        init(_ seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    /// 400 distinct words, so no n-gram of the stream repeats — any repeated
    /// n-gram in the merged output is duplicated overlap or invented text.
    /// Real streams repeat words; the test's must not.
    private static let vocab: [String] = (0..<400).map { "ka\($0)" }

    /// One randomized transcript scenario. A stream of `chunkCount * stepWords`
    /// distinct words; chunk i covers `[cuts[i], cuts[i+1] + overlap)` (the last
    /// chunk ends at the stream's end), so chunk i's last `overlap` words ARE
    /// chunk i+1's first `overlap` words — the real 20 s / 5 s chunk geometry.
    /// The re-heard copies are perturbed like ASR noise, per knob (per 1000):
    /// - `seamSubPerMille` of the copies are replaced by a random vocabulary
    ///   word (a mishearing; the alignment must tolerate the mismatch),
    /// - `copyDropPerMille` of the copies lose the word (one decode dropped
    ///   it: the alignment-gap case),
    /// - `seamDropPerMille` of the seam words are missing from BOTH copies
    ///   (unclear audio at the boundary — nothing to align there), drawn once
    ///   per seam word so it never models a one-copy loss by accident.
    /// Words outside a seam pass through untouched.
    private struct Scenario {
        let chunks: [[TimedSegment]]
        let stream: [String]
        /// Re-heard word count per boundary, in order.
        let seamOverlaps: [Int]
    }

    private func scenario(
        _ rng: inout SeededRNG, chunkCount: Int,
        seamSubPerMille: Int, copyDropPerMille: Int, seamDropPerMille: Int
    ) -> Scenario {
        let stepWords = 30 + Int(rng.next() % 50)  // own words per chunk
        let overlap = 8 + Int(rng.next() % 9)      // re-heard words per seam
        let n = chunkCount * stepWords
        var stream: [String] = []
        stream.reserveCapacity(n)
        var used = Set<Int>()
        for _ in 0..<n {
            var w = Int(rng.next() % UInt64(Self.vocab.count))
            while used.contains(w) { w = (w + 1) % Self.vocab.count }
            used.insert(w)
            stream.append(Self.vocab[w])
        }
        let cuts = (0...chunkCount).map { $0 * stepWords }
        var missing: Set<Int> = []
        for j in 1..<chunkCount {
            for k in cuts[j]..<cuts[j] + overlap where Int(rng.next() % 1000) < seamDropPerMille {
                missing.insert(k)
            }
        }
        func perturbed(_ k: Int, chunk i: Int, _ rng: inout SeededRNG) -> String? {
            let seamWithPrev = i > 0 && k >= cuts[i] && k < cuts[i] + overlap
            let seamWithNext = i < chunkCount - 1 && k >= cuts[i + 1] && k < cuts[i + 1] + overlap
            guard seamWithPrev || seamWithNext else { return stream[k] }
            if missing.contains(k) { return nil }
            if Int(rng.next() % 1000) < copyDropPerMille { return nil }
            if Int(rng.next() % 1000) < seamSubPerMille {
                return Self.vocab[Int(rng.next() % UInt64(Self.vocab.count))]
            }
            return stream[k]
        }
        var chunks: [[TimedSegment]] = []
        for i in 0..<chunkCount {
            let lo = cuts[i]
            let hi = i == chunkCount - 1 ? n : cuts[i + 1] + overlap
            var words: [String] = []
            for k in lo..<hi {
                if let w = perturbed(k, chunk: i, &rng) { words.append(w) }
            }
            // Segments of 4-10 words; word times 200 ms apart inside the chunk,
            // chunk i starting at i * 20 s, so the timeline stays monotone
            // across seams even before merging.
            var segments: [TimedSegment] = []
            var idx = 0
            let start = Int64(i) * 20_000
            while idx < words.count {
                let take = min(words.count - idx, 4 + Int(rng.next() % 7))
                let from = start + Int64(idx * 200)
                let to = start + Int64((idx + take) * 200) + 100
                segments.append(TimedSegment(startMs: from, endMs: to, text: words[idx..<(idx + take)].joined(separator: " ")))
                idx += take
            }
            chunks.append(segments)
        }
        return Scenario(chunks: chunks, stream: stream, seamOverlaps: Array(repeating: overlap, count: chunkCount - 1))
    }

    /// The normalized words of the merged output, and the invariant shared by
    /// every mode: the timeline never goes backwards.
    private func outputWords(_ result: SegmentMerge.Result, source: UInt64) -> [String] {
        var out: [String] = []
        var lastStart = Int64.min
        for s in result.segments {
            #expect(s.startMs >= lastStart, "backwards timeline at case \(source)")
            lastStart = s.startMs
            out.append(contentsOf: s.text.split(whereSeparator: { $0.isWhitespace }).map { SegmentMerge.normalize(String($0)) })
        }
        return out
    }

    /// No n-gram of the output may repeat. The stream's words are all
    /// distinct, so any repetition of an n-gram in the output is duplicated
    /// overlap (or invented text) — the signature the review's blocker left.
    private func assertNoRepeatedNgram(_ out: [String], n: Int, source: UInt64) {
        guard out.count >= n else { return }
        var seen = Set<[String]>()
        for k in 0...(out.count - n) {
            let gram = Array(out[k..<(k + n)])
            #expect(!seen.contains(gram), "repeated \(n)-gram at case \(source), position \(k): \(gram)")
            seen.insert(gram)
        }
    }

    /// Review blocker: a time-capped alignment window duplicated the overlap
    /// at 37% of randomized boundaries (1488/4000) — the capped window missed
    /// the aligned run and both chunks joined whole. The Android structure
    /// (the last/first 45 words of the accumulated list, no time cap) must
    /// never duplicate. With IDENTICAL re-heard copies the full seam always
    /// scores 2·overlap ≥ 16 ≥ MIN_SCORE with overlap ≥ MIN_MATCHES matches,
    /// so every boundary must stitch, the middle cut must keep each seam word
    /// exactly once, and the merged output must BE the stream: same word
    /// count, attribution `removedWords == [0, overlap, …]`, both ends intact,
    /// no repeated 3-gram anywhere.
    @Test func randomizedIdenticalOverlapsMergeExactly() {
        var rng = SeededRNG(2026_11_16)
        for caseIndex in 0..<4000 {
            let s = scenario(&rng, chunkCount: 2, seamSubPerMille: 0, copyDropPerMille: 0, seamDropPerMille: 0)
            let result = SegmentMerge.merge(chunks: s.chunks, overlapMs: 5_000)
            let out = outputWords(result, source: UInt64(caseIndex))
            #expect(out.count == s.stream.count, "case \(caseIndex): \(out.count) words, stream has \(s.stream.count)")
            assertNoRepeatedNgram(out, n: 3, source: UInt64(caseIndex))
            #expect(result.removedWords == [0] + s.seamOverlaps, "case \(caseIndex): \(result.removedWords) vs [0] + \(s.seamOverlaps)")
            #expect(out.prefix(3) == s.stream.prefix(3), "case \(caseIndex): head changed")
            #expect(out.suffix(3) == s.stream.suffix(3), "case \(caseIndex): tail changed")
        }
        for caseIndex in 0..<2000 {
            let s = scenario(&rng, chunkCount: 3, seamSubPerMille: 0, copyDropPerMille: 0, seamDropPerMille: 0)
            let result = SegmentMerge.merge(chunks: s.chunks, overlapMs: 5_000)
            let out = outputWords(result, source: 1_000_000 + UInt64(caseIndex))
            #expect(out.count == s.stream.count, "case \(caseIndex): \(out.count) words, stream has \(s.stream.count)")
            assertNoRepeatedNgram(out, n: 3, source: 1_000_000 + UInt64(caseIndex))
            #expect(result.removedWords == [0] + s.seamOverlaps, "case \(caseIndex): \(result.removedWords) vs [0] + \(s.seamOverlaps)")
            #expect(out.prefix(3) == s.stream.prefix(3), "case \(caseIndex): head changed")
            #expect(out.suffix(3) == s.stream.suffix(3), "case \(caseIndex): tail changed")
        }
    }

    /// The same geometry under realistic noise: a few misheard words
    /// (substitutions — the alignment scores them -1 each), a few single-copy
    /// losses (alignment gaps), a few seam words missing from both copies.
    /// The rates stay low enough that the full seam clears MIN_SCORE at
    /// essentially every boundary; a boundary that legitimately fails
    /// MIN_SCORE joins whole (Android-faithful), which is not the defect this
    /// test is about. What must never happen is WIDE duplication: no repeated
    /// 5-gram anywhere, and the output never exceeds the stream by more than
    /// a few words per seam — a middle cut under gaps can double one or two
    /// words; a whole join would double the whole ≥ 8-word overlap.
    @Test func randomizedNoisyOverlapsNeverDuplicateWide() {
        var rng = SeededRNG(777)
        for caseIndex in 0..<500 {
            let s = scenario(&rng, chunkCount: 2, seamSubPerMille: 30, copyDropPerMille: 5, seamDropPerMille: 10)
            let result = SegmentMerge.merge(chunks: s.chunks, overlapMs: 5_000)
            let out = outputWords(result, source: 2_000_000 + UInt64(caseIndex))
            assertNoRepeatedNgram(out, n: 5, source: 2_000_000 + UInt64(caseIndex))
            #expect(out.count <= s.stream.count + 6, "case \(caseIndex): \(out.count) words vs stream \(s.stream.count)")
        }
        for caseIndex in 0..<300 {
            let s = scenario(&rng, chunkCount: 3, seamSubPerMille: 30, copyDropPerMille: 5, seamDropPerMille: 10)
            let result = SegmentMerge.merge(chunks: s.chunks, overlapMs: 5_000)
            let out = outputWords(result, source: 3_000_000 + UInt64(caseIndex))
            assertNoRepeatedNgram(out, n: 5, source: 3_000_000 + UInt64(caseIndex))
            #expect(out.count <= s.stream.count + 12, "case \(caseIndex): \(out.count) words vs stream \(s.stream.count)")
        }
    }

    /// Hand cases from the review: fully-matching overlap, repeated identical
    /// words across the cut, a perturbed overlap, and an empty middle chunk —
    /// the per-chunk-drop structure could not stitch across it (the boundary
    /// after an empty chunk saw the empty chunk as `prev` and gave up);
    /// Android's accumulated tail stitches it.
    @Test func identicalOverlapCollapsesOnce() {
        let first = chunk(["alpha", "bravo", "charlie", "delta", "echo", "foxtrot"], fromMs: 0)
        let second = chunk(["charlie", "delta", "echo", "foxtrot", "golf", "hotel"], fromMs: 15_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5_000)
        let text = result.segments.map(\.text).joined(separator: " ")
        for w in ["charlie", "delta", "echo", "foxtrot"] {
            #expect(text.components(separatedBy: w).count - 1 == 1, "\(w) must occur exactly once")
        }
        #expect(text.contains("alpha") && text.contains("golf") && text.contains("hotel"))
    }

    @Test func repeatedWordsAcrossTheCutDoNotDuplicate() {
        let first = chunk(["okay", "yeah", "yeah", "yeah", "well"], fromMs: 0)
        let second = chunk(["yeah", "yeah", "yeah", "right", "sure"], fromMs: 15_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5_000)
        let text = result.segments.map(\.text).joined(separator: " ")
        // Android-faithful: the seam's best run is 3 equal words scoring
        // 2·3 − 2·1 = 4 < MIN_SCORE (6), so this boundary joins WHOLE and all
        // six copies of "yeah" survive — the blocker's defect was words
        // growing PAST the input, never fewer than it. Ends intact, timeline
        // monotone.
        #expect(text.components(separatedBy: "yeah").count - 1 == 6)
        #expect(text.contains("okay") && text.contains("well") && text.contains("right") && text.contains("sure"))
        var lastStart = Int64.min
        for s in result.segments {
            #expect(s.startMs >= lastStart)
            lastStart = s.startMs
        }
    }

    @Test func oneSubstitutionStillStitches() {
        let first = chunk(["the", "quick", "brown", "fox", "jumps", "over", "lazy", "dogs"], fromMs: 0)
        // "dogs" heard as "dog" (one edit, 5+ chars → similar), rest identical.
        let second = chunk(["brown", "fox", "jumps", "over", "lazy", "dog", "and", "rests"], fromMs: 15_000)
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5_000)
        let text = result.segments.map(\.text).joined(separator: " ")
        #expect(text.contains("the") && text.contains("rests"))
        // The duplicated head is gone: "brown" appears once.
        #expect(text.components(separatedBy: "brown").count - 1 == 1)
    }

    @Test func emptyMiddleChunkDoesNotBreakTheNextStitch() {
        let first = chunk(["alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf"], fromMs: 0)
        let middle: [TimedSegment] = []  // silence: nothing recognized here
        let third = chunk(["echo", "foxtrot", "golf", "hotel", "india", "juliet"], fromMs: 40_000)
        let result = SegmentMerge.merge(chunks: [first, middle, third], overlapMs: 5_000)
        let text = result.segments.map(\.text).joined(separator: " ")
        // The chunk-0/chunk-2 overlap is removed exactly once even though the
        // stitch had to reach across the empty chunk.
        for w in ["echo", "foxtrot", "golf"] {
            #expect(text.components(separatedBy: w).count - 1 == 1, "\(w) must occur exactly once")
        }
        #expect(text.contains("alpha") && text.contains("juliet"))
        #expect(result.removedWords.count == 3)
    }

    @Test func singleWordSegmentsMerge() {
        let first = [TimedSegment(startMs: 0, endMs: 2_000, text: "hello"),
                     TimedSegment(startMs: 2_000, endMs: 4_000, text: "there"),
                     TimedSegment(startMs: 4_000, endMs: 6_000, text: "friend")]
        let second = [TimedSegment(startMs: 15_000, endMs: 17_000, text: "friend"),
                      TimedSegment(startMs: 17_000, endMs: 19_000, text: "of"),
                      TimedSegment(startMs: 19_000, endMs: 21_000, text: "mine")]
        let result = SegmentMerge.merge(chunks: [first, second], overlapMs: 5_000)
        let text = result.segments.map(\.text).joined(separator: " ")
        // Android-faithful: only one equal word ("friend") exists in the seam,
        // 1 match < MIN_MATCHES (3) — no stitch, the chunks join whole and
        // "friend" legitimately appears once per chunk. Non-multiplication is
        // the invariant: never more copies than the input had.
        #expect(text.components(separatedBy: "friend").count - 1 == 2)
        #expect(text.contains("hello") && text.contains("mine"))
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

    /// The finished download is put in place with one `rename(2)` in the same
    /// directory: the destination holds the old file or the new one, never
    /// neither. (The old code removed the destination and then moved.)
    @Test func verifiedFileReplacesInOneStep() throws {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("mindvault-atomic-\(UUID().uuidString)")
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: dir) }

        let part = dir.appendingPathComponent("model.bin.part")
        let destination = dir.appendingPathComponent("model.bin")
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("new verified bytes".utf8).write(to: part)
        try Data("old stale model".utf8).write(to: destination)

        try URLTransfer.replaceAtomically(part, destination)

        #expect(try Data(contentsOf: destination) == Data("new verified bytes".utf8))
        #expect(!fm.fileExists(atPath: part.path), "the .part must be gone (renamed, not copied)")
        #expect(try fm.contentsOfDirectory(atPath: dir.path).sorted() == ["model.bin"],
                "no backup or leftover files in the model directory")
    }

    @Test func replaceFailsLoudlyWhenTheDirectoryIsGone() {
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("mindvault-absent-\(UUID().uuidString)/x.part")
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("mindvault-absent-\(UUID().uuidString)/x")
        #expect(throws: URLTransferError.self) {
            try URLTransfer.replaceAtomically(missing, destination)
        }
    }

    /// A failure log names the file being fetched, without the query string —
    /// that is where a pre-signed model URL keeps its signature.
    @Test func failureLogURLDropsTheQuery() {
        #expect(URLTransfer.logURL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin")
            == "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin")
        #expect(URLTransfer.logURL("https://example.test/a.bin?X-Amz-Signature=deadbeef&X-Amz-Credential=sekrit#frag")
            == "https://example.test/a.bin")
        #expect(URLTransfer.logURL("not a url") == "not a url")
    }

    /// An oversize body is not a short download; both numbers are reported.
    @Test func oversizeBodyStatesTheExpectation() {
        let error = URLTransferError.oversizeBody(received: 1_005_000, expected: 1_000_000)
        #expect(error != URLTransferError.shortDownload(have: 1_005_000, expected: 1_000_000))
        if case let .oversizeBody(received, expected) = error {
            #expect(received == 1_005_000)
            #expect(expected == 1_000_000)
        } else {
            Issue.record("expected .oversizeBody")
        }
    }
}
