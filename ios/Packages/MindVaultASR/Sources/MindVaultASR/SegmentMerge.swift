import Foundation

/// One stretch of recognized speech with its place in the recording. This is the
/// timed unit the app stores and shows; words are the text as the ASR wrote it.
public struct TimedSegment: Equatable, Sendable, Hashable {
    public var startMs: Int64
    public var endMs: Int64
    public var text: String

    public init(startMs: Int64, endMs: Int64, text: String) {
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
    }
}

/// Merging the whisper output of consecutive recording chunks (20 s chunks, 5 s
/// overlap, `ChunkMath` on the app side) into one session timeline.
///
/// Every chunk is transcribed on its own, so the words in the overlap are
/// recognized twice, usually a little differently. The overlap is removed by the
/// same word alignment the Android `data/TranscriptMerge.kt` stitch uses — the
/// tuning that was measured on the recordings (window 45 words, match +2,
/// mismatch/gap -1, minimum score 6, minimum 3 matches, cut in the middle of the
/// alignment) — but on timed words: each word keeps its interpolated time from
/// its whisper segment, and what survives becomes timed segments again.
/// (MindVaultCore `TranscriptMerge` merges untimed chunk texts; the app may use
/// either — see INTEGRATION.md. Pure logic, Linux-tested.)
public enum SegmentMerge {
    /// Word count of the alignment window at a chunk boundary.
    static let maxWords = 45
    static let matchScore = 2
    static let mismatchScore = -1
    static let gapScore = -1
    static let minScore = 6
    static let minMatches = 3

    private static let diagonal = 0
    private static let up = 1
    private static let left = 2

    /// One word of one whisper segment with its time.
    public struct TimedWord: Equatable, Sendable {
        public let text: String
        public let startMs: Int64
        public let endMs: Int64
        /// Index of the segment inside its chunk, so surviving words can be
        /// grouped back into the segments the model produced.
        public let segmentIndex: Int
    }

    public struct Result: Equatable, Sendable {
        /// The merged timeline: timed segments in order, no duplicated overlap.
        public let segments: [TimedSegment]
        /// Words removed per chunk (end-trimmed + start-trimmed), for the trace.
        public let removedWords: [Int]
    }

    /// Splits a segment's text into words and spreads the segment's time evenly
    /// over them (whisper times a segment, not a word).
    public static func timedWords(_ segment: TimedSegment, segmentIndex: Int) -> [TimedWord] {
        let words = segment.text.split(whereSeparator: { $0.isWhitespace })
        guard !words.isEmpty else { return [] }
        let span = max(segment.endMs - segment.startMs, 0)
        let per = Double(span) / Double(words.count)
        return words.enumerated().map { i, word in
            TimedWord(
                text: String(word),
                startMs: segment.startMs + Int64((per * Double(i)).rounded()),
                endMs: segment.startMs + Int64((per * Double(i + 1)).rounded()),
                segmentIndex: segmentIndex
            )
        }
    }

    /// Merges the per-chunk segment lists (chunk i covers
    /// `[i*stepMs, i*stepMs + chunkMs)`, so chunk i+1's first `overlapMs`
    /// re-hear chunk i's last `overlapMs`). Chunks with no text pass through
    /// untouched.
    public static func merge(chunks: [[TimedSegment]], overlapMs: Int64) -> Result {
        // Flatten every chunk into timed words, remembering each word's segment.
        let allWords = chunks.map { chunk -> [TimedWord] in
            chunk.enumerated().flatMap { i, segment in timedWords(segment, segmentIndex: i) }
        }

        // Per boundary: how many words to drop from the end of the earlier chunk
        // and from the start of the later one.
        var dropEnd = [Int](repeating: 0, count: chunks.count)
        var dropStart = [Int](repeating: 0, count: chunks.count)

        for i in 1..<allWords.count {
            let prev = allWords[i - 1]
            let next = allWords[i]
            guard !prev.isEmpty, !next.isEmpty else { continue }
            if let stitch = stitch(accumulated: prev, next: next, overlapMs: overlapMs) {
                dropEnd[i - 1] = stitch.dropFromEnd
                dropStart[i] = stitch.dropFromStart
            }
        }

        // Rebuild: surviving words of each chunk, grouped back into segments.
        var segments: [TimedSegment] = []
        var removed: [Int] = []
        for i in 0..<chunks.count {
            let words = allWords[i]
            let from = dropStart[i]
            let to = words.count - dropEnd[i]
            removed.append(max(0, dropEnd[i]) + max(0, dropStart[i]))
            guard from < to else { continue }
            var group: [TimedWord] = []
            var flush: () -> Void = {}
            flush = {
                if !group.isEmpty {
                    segments.append(
                        TimedSegment(
                            startMs: group.first!.startMs,
                            endMs: group.last!.endMs,
                            text: group.map(\.text).joined(separator: " ")
                        )
                    )
                    group = []
                }
            }
            for j in from..<to {
                let w = words[j]
                // A boundary inside one original segment (consecutive words with
                // the same segment index) stays one segment; otherwise split.
                if let last = group.last, last.segmentIndex != w.segmentIndex {
                    flush()
                }
                group.append(w)
            }
            flush()
        }
        return Result(segments: segments, removedWords: removed)
    }

    struct Stitch {
        let dropFromEnd: Int
        let dropFromStart: Int
    }

    /// The Android `TranscriptMerge.stitch` alignment, on timed words: align the
    /// tail of the accumulated words against the head of the next chunk's words
    /// and cut both in the middle of the aligned run. The window is capped to the
    /// words that can lie in the overlap on either side, so a long silence at a
    /// boundary cannot pull unrelated text into the match.
    static func stitch(accumulated: [TimedWord], next: [TimedWord], overlapMs: Int64) -> Stitch? {
        // Words that plausibly sit inside the overlap: at most overlapMs worth of
        // audio, at least one.
        func overlapCount(_ words: [TimedWord], trailing: Bool) -> Int {
            guard overlapMs > 0, !words.isEmpty else { return trailing ? min(1, words.count) : min(1, words.count) }
            let boundary = trailing ? words.last!.endMs : words.first!.startMs
            var count = 0
            if trailing {
                for w in words.reversed() {
                    if boundary - w.startMs > overlapMs { break }
                    count += 1
                    if count >= maxWords { break }
                }
            } else {
                for w in words {
                    if w.endMs - boundary > overlapMs { break }
                    count += 1
                    if count >= maxWords { break }
                }
            }
            return max(1, min(count, maxWords))
        }

        let a = accumulated.suffix(overlapCount(accumulated, trailing: true)).map(\.text)
        let b = next.prefix(overlapCount(next, trailing: false)).map(\.text)
        guard !a.isEmpty, !b.isEmpty else { return nil }

        let n = a.count
        let m = b.count
        var score = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        var direction = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        var matches = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)

        if m > 0 {
            for j in 1...m {
                score[0][j] = j * gapScore
                direction[0][j] = left
            }
        }
        if n > 0 {
            for i in 1...n {
                for j in 1...m {
                    let same = similar(normalize(a[i - 1]), normalize(b[j - 1]))
                    let diag = score[i - 1][j - 1] + (same ? matchScore : mismatchScore)
                    let upScore = score[i - 1][j] + gapScore
                    let leftScore = score[i][j - 1] + gapScore
                    let best = max(diag, upScore, leftScore)
                    score[i][j] = best
                    if best == diag {
                        direction[i][j] = diagonal
                        matches[i][j] = matches[i - 1][j - 1] + (same ? 1 : 0)
                    } else if best == upScore {
                        direction[i][j] = up
                        matches[i][j] = matches[i - 1][j]
                    } else {
                        direction[i][j] = left
                        matches[i][j] = matches[i][j - 1]
                    }
                }
            }
        }

        // The best alignment that ends somewhere inside the next chunk's head.
        var bestJ = 0
        var bestScore = 0
        if m > 0 {
            for j in 1...m where score[n][j] >= bestScore && score[n][j] > 0 && matches[n][j] >= minMatches {
                bestScore = score[n][j]
                bestJ = j
            }
        }
        guard bestScore >= minScore else { return nil }

        var pairs: [(Int, Int)] = []
        var i = n
        var j = bestJ
        while j > 0 && i > 0 {
            switch direction[i][j] {
            case diagonal:
                pairs.append((i - 1, j - 1))
                i -= 1
                j -= 1
            case up:
                i -= 1
            default:
                j -= 1
            }
        }
        guard !pairs.isEmpty else { return Stitch(dropFromEnd: 0, dropFromStart: bestJ) }
        pairs.reverse()
        let middle = pairs[pairs.count / 2]
        return Stitch(dropFromEnd: n - middle.0, dropFromStart: middle.1)
    }

    /// Fold to lowercase alphanumerics, the same word normalization as the
    /// Android stitch (`TextNormalization.words` folded). A loop, not
    /// `filter`, so no Foundation `Predicate` overload is in the way.
    static func normalize(_ word: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in word.lowercased().unicodeScalars where Character(scalar).isLetter || Character(scalar).isNumber {
            out.append(scalar)
        }
        return String(out)
    }

    /// Equal, or one edit apart for long words (5+ characters), like the Android
    /// `TranscriptMerge.similar` — ASR often spells a name one letter differently
    /// between two decodes of the same audio.
    static func similar(_ lhs: String, _ rhs: String) -> Bool {
        if lhs == rhs { return !lhs.isEmpty }
        if lhs.count < 5 || rhs.count < 5 { return false }
        if abs(lhs.count - rhs.count) > 1 { return false }
        return editDistanceAtMostOne(lhs, rhs)
    }

    static func editDistanceAtMostOne(_ lhs: String, _ rhs: String) -> Bool {
        let x = Array(lhs)
        let y = Array(rhs)
        var i = 0
        var j = 0
        var edits = 0
        while i < x.count && j < y.count {
            if x[i] == y[j] {
                i += 1
                j += 1
            } else {
                edits += 1
                if edits > 1 { return false }
                if x.count > y.count {
                    i += 1
                } else if x.count < y.count {
                    j += 1
                } else {
                    i += 1
                    j += 1
                }
            }
        }
        return edits + (x.count - i) + (y.count - j) <= 1
    }
}
