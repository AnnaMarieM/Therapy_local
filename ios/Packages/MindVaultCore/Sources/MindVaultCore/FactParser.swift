import Foundation
import MindVaultModels

public struct ParsedFact: Codable, Hashable, Sendable {
    public var heading: String
    public var content: String
    public var evidence: String
    public var segmentIndex: Int

    public init(heading: String, content: String, evidence: String, segmentIndex: Int = 0) {
        self.heading = heading
        self.content = content
        self.evidence = evidence
        self.segmentIndex = segmentIndex
    }
}

public struct DuplicateVerdict: Codable, Hashable, Sendable {
    public var duplicate: Bool
    public var of: Int?

    public init(duplicate: Bool, of: Int?) {
        self.duplicate = duplicate
        self.of = of
    }

    public func encoded() -> String {
        duplicate ? "dup:\(of ?? 0)" : "new"
    }

    public static func decoded(_ value: String) -> DuplicateVerdict? {
        if value == "new" { return DuplicateVerdict(duplicate: false, of: nil) }
        guard value.hasPrefix("dup:") else { return nil }
        guard let index = Int(value.dropFirst(4)) else { return nil }
        return DuplicateVerdict(duplicate: true, of: index)
    }
}

public enum FactParser {
    private static let pairPattern = #""(heading|content|evidence)"\s*:\s*"((?:[^"\\]|\\.)*)""#
    private static let trueFalsePattern = #"(?i)\b(true|false)\b"#

    public static func jsonObject(raw: String) -> Any? {
        let cleaned = cleanedJSONText(raw)
        guard !cleaned.isEmpty else { return nil }
        if let data = cleaned.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) {
            return object
        }
        return repairTruncated(cleaned)
    }

    public static func records(text: String) -> [[String: String]] {
        guard let regex = try? NSRegularExpression(pattern: pairPattern) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        var result: [[String: String]] = []
        var current: [String: String] = [:]
        for match in regex.matches(in: text, range: range) {
            guard match.numberOfRanges == 3,
                  let keyRange = Range(match.range(at: 1), in: text),
                  let valueRange = Range(match.range(at: 2), in: text) else { continue }
            let key = String(text[keyRange])
            if current[key] != nil {
                result.append(current)
                current = [:]
            }
            current[key] = unescape(String(text[valueRange]))
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    public static func valid(_ fact: ParsedFact) -> Bool {
        !fact.heading.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && fact.content.count >= 10
            && fact.evidence.count >= 5
    }

    public static func anyFacts(text: String) -> [ParsedFact] {
        records(text: text).compactMap { record in
            let fact = ParsedFact(
                heading: record["heading"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
                content: record["content"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
                evidence: record["evidence"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            )
            return valid(fact) ? fact : nil
        }
    }

    public static func evidenceHolds(_ fact: ParsedFact, source: String) -> Bool {
        let evidence = TextNormalization.normalize(fact.evidence)
        guard !evidence.isEmpty else { return false }
        return TextNormalization.normalize(source).contains(evidence)
    }

    public static func segmentOf(evidence: String, segments: [TranscriptSegment]) -> Int? {
        let quote = TextNormalization.normalize(evidence)
        guard !quote.isEmpty else { return nil }
        var joined = ""
        var ends: [Int] = []
        for segment in segments {
            if !joined.isEmpty { joined.append(" ") }
            joined.append(TextNormalization.normalize(segment.text))
            ends.append(joined.count)
        }
        guard let range = joined.range(of: quote) else { return nil }
        let offset = joined.distance(from: joined.startIndex, to: range.lowerBound)
        guard let index = ends.firstIndex(where: { offset < $0 }) else { return nil }
        return segments[index].chunkIndex
    }

    public static func verdict(raw: String, known: Int) -> DuplicateVerdict? {
        let object = jsonObject(raw: raw) as? [String: Any]
        let duplicate: Bool?
        switch object?["duplicate"] {
        case let value as Bool:
            duplicate = value
        case let value as String:
            if value.caseInsensitiveCompare("true") == .orderedSame {
                duplicate = true
            } else if value.caseInsensitiveCompare("false") == .orderedSame {
                duplicate = false
            } else {
                duplicate = nil
            }
        default:
            let range = NSRange(raw.startIndex..<raw.endIndex, in: raw)
            if let match = try? NSRegularExpression(pattern: trueFalsePattern).firstMatch(in: raw, range: range),
               let matchRange = Range(match.range(at: 1), in: raw) {
                duplicate = String(raw[matchRange]).caseInsensitiveCompare("true") == .orderedSame
            } else {
                duplicate = nil
            }
        }
        guard let duplicate else { return nil }
        guard duplicate else { return DuplicateVerdict(duplicate: false, of: nil) }
        let rawIndex = (object?["of"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "K", with: "")
            ?? String(describing: object?["of"] ?? "")
        let index = Int(rawIndex).map { $0 - 1 }.flatMap { $0 >= 0 && $0 < max(0, known) ? $0 : nil } ?? 0
        return DuplicateVerdict(duplicate: true, of: index)
    }

    private static func cleanedJSONText(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("```json") { text.removeFirst(7) }
        if text.hasPrefix("```") { text.removeFirst(3) }
        if text.hasSuffix("```") { text.removeLast(3) }
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end else {
            return ""
        }
        return String(text[start...end]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func repairTruncated(_ text: String) -> Any? {
        guard let cut = text.range(of: "},", options: .backwards) else { return nil }
        let repaired = String(text[...cut.lowerBound]) + "}]}"
        guard let data = repaired.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func unescape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\\"", with: "\"")
            .replacingOccurrences(of: "\\n", with: " ")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }
}
