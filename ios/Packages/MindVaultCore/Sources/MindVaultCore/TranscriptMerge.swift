import Foundation

public struct TranscriptMergeResult: Equatable, Sendable {
    public let text: String
    public let removedWords: [Int]
    public let kept: [ClosedRange<Int>?]

    public init(text: String, removedWords: [Int], kept: [ClosedRange<Int>?]) {
        self.text = text
        self.removedWords = removedWords
        self.kept = kept
    }
}

public enum TranscriptMerge {
    private static let maxWords = 45
    private static let matchScore = 2
    private static let mismatchScore = -1
    private static let gapScore = -1
    private static let minScore = 6
    private static let minMatches = 3

    private static let diagonal = 0
    private static let up = 1
    private static let left = 2

    private struct Stitch {
        let dropFromEnd: Int
        let dropFromStart: Int
    }

    public static func words(_ text: String) -> [String] {
        TextNormalization.words(text)
    }

    public static func merge(_ texts: [String]) -> TranscriptMergeResult {
        var words: [String] = []
        var origin: [Int] = []
        var position: [Int] = []
        var removed: [Int] = []

        for (chunkIndex, text) in texts.enumerated() {
            let next = TranscriptMerge.words(text)
            guard !words.isEmpty else {
                append(chunkIndex, next, from: 0, words: &words, origin: &origin, position: &position)
                removed.append(0)
                continue
            }
            guard let stitch = stitch(accumulated: words, next: next) else {
                append(chunkIndex, next, from: 0, words: &words, origin: &origin, position: &position)
                removed.append(0)
                continue
            }
            if stitch.dropFromEnd > 0 {
                words.removeLast(stitch.dropFromEnd)
                origin.removeLast(stitch.dropFromEnd)
                position.removeLast(stitch.dropFromEnd)
            }
            append(chunkIndex, next, from: stitch.dropFromStart, words: &words, origin: &origin, position: &position)
            removed.append(stitch.dropFromEnd + stitch.dropFromStart)
        }

        var first = Array(repeating: -1, count: texts.count)
        var last = Array(repeating: -1, count: texts.count)
        for wordIndex in origin.indices {
            let chunk = origin[wordIndex]
            if first[chunk] < 0 { first[chunk] = position[wordIndex] }
            last[chunk] = position[wordIndex]
        }
        let kept: [ClosedRange<Int>?] = texts.indices.map { index in
            guard first[index] >= 0, last[index] >= first[index] else { return nil }
            return first[index]...last[index]
        }
        return TranscriptMergeResult(text: words.joined(separator: " "), removedWords: removed, kept: kept)
    }

    private static func append(
        _ chunk: Int,
        _ next: [String],
        from: Int,
        words: inout [String],
        origin: inout [Int],
        position: inout [Int]
    ) {
        guard from < next.count else { return }
        for index in from..<next.count {
            words.append(next[index])
            origin.append(chunk)
            position.append(index)
        }
    }

    private static func stitch(accumulated: [String], next: [String]) -> Stitch? {
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

    private static func normalize(_ word: String) -> String {
        let scalars = word.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }
        return String(String.UnicodeScalarView(scalars))
    }

    private static func similar(_ lhs: String, _ rhs: String) -> Bool {
        if lhs == rhs { return !lhs.isEmpty }
        if lhs.count < 5 || rhs.count < 5 { return false }
        if abs(lhs.count - rhs.count) > 1 { return false }
        return editDistanceAtMostOne(lhs, rhs)
    }

    private static func editDistanceAtMostOne(_ lhs: String, _ rhs: String) -> Bool {
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
