import Foundation
import MindVaultModels

public struct TranscriptWindowSpan: Equatable, Sendable {
    public let segFrom: Int
    public let segTo: Int
    public let text: String

    public init(segFrom: Int, segTo: Int, text: String) {
        self.segFrom = segFrom
        self.segTo = segTo
        self.text = text
    }
}

public enum TranscriptWindows {
    public static let tokensPerWord = 1.4

    public static func bySegments(
        segments: [TranscriptSegment],
        windowWords: Int,
        overlapWords: Int
    ) -> [TranscriptWindowSpan] {
        precondition(windowWords > overlapWords && overlapWords >= 0, "windowWords must exceed overlapWords")
        var words: [String] = []
        var segmentIndex: [Int] = []
        for segment in segments {
            for word in TextNormalization.words(segment.text) {
                words.append(word)
                segmentIndex.append(segment.chunkIndex)
            }
        }
        guard !words.isEmpty else { return [] }

        var spans: [TranscriptWindowSpan] = []
        var start = 0
        while start < words.count {
            let end = min(start + windowWords, words.count)
            spans.append(
                TranscriptWindowSpan(
                    segFrom: segmentIndex[start],
                    segTo: segmentIndex[end - 1],
                    text: words[start..<end].joined(separator: " ")
                )
            )
            if end == words.count { break }
            start += windowWords - overlapWords
        }
        return spans
    }

    public static func wordsFor(tokens: Int) -> Int {
        Int(Double(max(0, tokens)) / tokensPerWord)
    }

    public static func stripSpeakerLabels(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?m)^\s*\**[A-Z][A-Za-z.]*\**:\**\s*"#,
            with: "",
            options: .regularExpression
        )
    }
}
