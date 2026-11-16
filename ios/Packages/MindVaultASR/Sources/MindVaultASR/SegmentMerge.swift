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
/// same word alignment the Android `data/TranscriptMerge.kt` stitch uses, and in
/// the same structure: ONE accumulated word list mutated at every boundary
/// (drop the aligned tail, append the next chunk from its cut), the window is
/// the last/first 45 words with no time cap, match +2, mismatch/gap -1,
/// minimum score 6, minimum 3 matches, cut in the middle of the alignment —
/// the tuning measured on the recordings. A time cap derived from word timings
/// (an earlier draft) duplicated the overlap at 37% of randomized boundaries:
/// whisper's word times drift, so the capped window misses the aligned run and
/// both chunks join whole. On timed words: each word keeps its interpolated
/// time from its whisper segment, and what survives becomes timed segments
/// again. (MindVaultCore `TranscriptMerge` merges untimed chunk texts; the app
/// may use either — see INTEGRATION.md. Pure logic, Linux-tested.)
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
        /// Words removed at each append, Android's `removedWords`: for chunk i,
        /// the tail dropped from the accumulated words plus the head dropped
        /// from chunk i itself (0 if no overlap was found or the chunk is the
        /// first). The tail drop may span more than one earlier chunk when an
        /// earlier chunk contributed few words.
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
    /// untouched. The chunk times must be recording-absolute (the caller
    /// offsets chunk-local whisper times before merging).
    ///
    /// Android `TranscriptMerge.merge` structure: one accumulated word list,
    /// mutated at every boundary — the stitch's `dropFromEnd` words are removed
    /// from the accumulated tail (which may span several earlier chunks, e.g.
    /// after an empty chunk) and the next chunk's words are appended from its
    /// own `dropFromStart`. The stitch aligns against everything so far, not
    /// just the previous chunk, so an empty chunk cannot break the next
    /// boundary's alignment.
    public static func merge(chunks: [[TimedSegment]], overlapMs: Int64) -> Result {
        // (chunk, segmentIndex) per accumulated word, so survivors group back
        // into the segments the model produced.
        var words: [TimedWord] = []
        var origins: [(chunk: Int, segmentIndex: Int)] = []
        var removed: [Int] = []

        for (i, chunk) in chunks.enumerated() {
            let next = chunk.enumerated().flatMap { si, segment in timedWords(segment, segmentIndex: si) }
            // `cut`, not `stitch`: a local named `stitch` would shadow the
            // stitch function inside its own initializer.
            let cut = words.isEmpty ? nil : stitch(accumulated: words.map(\.text), next: next.map(\.text))
            if let cut {
                if cut.dropFromEnd > 0 {
                    words.removeLast(cut.dropFromEnd)
                    origins.removeLast(cut.dropFromEnd)
                }
                for w in next.dropFirst(cut.dropFromStart) {
                    words.append(w)
                    origins.append((chunk: i, segmentIndex: w.segmentIndex))
                }
                removed.append(cut.dropFromEnd + cut.dropFromStart)
            } else {
                for w in next {
                    words.append(w)
                    origins.append((chunk: i, segmentIndex: w.segmentIndex))
                }
                removed.append(0)
            }
        }

        // Survivors group back into timed segments: consecutive runs of the
        // same (chunk, segmentIndex). Drops only ever take a suffix of the
        // accumulated list and a prefix of the appended chunk, so every run is
        // contiguous — the timed analogue of Android's kept ranges.
        var segments: [TimedSegment] = []
        var runStart = 0
        while runStart < words.count {
            var runEnd = runStart + 1
            while runEnd < words.count,
                  origins[runEnd].chunk == origins[runStart].chunk,
                  origins[runEnd].segmentIndex == origins[runStart].segmentIndex
            {
                runEnd += 1
            }
            let run = words[runStart..<runEnd]
            segments.append(
                TimedSegment(
                    startMs: run.first!.startMs,
                    endMs: run.last!.endMs,
                    text: run.map(\.text).joined(separator: " ")
                )
            )
            runStart = runEnd
        }
        return Result(segments: segments, removedWords: removed)
    }

    struct Stitch {
        let dropFromEnd: Int
        let dropFromStart: Int
    }

    /// The Android `TranscriptMerge.stitch` alignment: the tail of the words so
    /// far against the head of the next chunk's words. The window is the
    /// last/first 45 words ONLY — no time cap. Whisper's word times drift, so a
    /// window capped by `overlapMs` of audio missed the aligned run at 37% of
    /// randomized boundaries (review: both chunks then joined whole and the
    /// overlap was duplicated); the word-count window measured 0 wrong.
    static func stitch(accumulated: [String], next: [String]) -> Stitch? {
        let a = accumulated.suffix(maxWords).map(normalize)
        let b = next.prefix(maxWords).map(normalize)
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
                    // a/b are normalized above once, like Kotlin line 99 (no re-norm per cell).
                    let same = similar(a[i - 1], b[j - 1])
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

    /// Fold to lowercase letters and ASCII digits, the word normalization the
    /// Android stitch applies to both sides (`TranscriptMerge.norm` folds with
    /// `isLetterOrDigit`). The digit set is narrower on purpose — ASCII 0–9
    /// only: ASR output digits are ASCII, and `Character.isNumber` also accepts
    /// the Nl/No categories ("Ⅻ", "²") that a transcript never carries, while
    /// Kotlin's fold keeps all Nd digits (so an Arabic-Indic "٣" survives on
    /// Android, never on iOS). Irrelevant for real transcripts, simpler to
    /// reason about. A loop, not `filter`, so no Foundation `Predicate`
    /// overload is in the way.
    static func normalize(_ word: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in word.lowercased().unicodeScalars
        where Character(scalar).isLetter || (48...57).contains(scalar.value) {
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
