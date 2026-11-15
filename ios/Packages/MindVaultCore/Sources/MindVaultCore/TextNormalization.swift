import Foundation

public enum TextNormalization {
    public static func normalize(_ text: String) -> String {
        let lowered = text.lowercased()
        let scalars = lowered.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) ? Character(String(scalar)) : " "
        }
        return String(scalars)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    public static func tokens(_ text: String) -> [String] {
        let normalized = normalize(text)
        guard !normalized.isEmpty else { return [] }
        return normalized.split(separator: " ").map(String.init)
    }

    public static func words(_ text: String) -> [String] {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
            .filter { !$0.isEmpty }
    }

    public static func isSubstring(_ needle: String, of haystack: String) -> Bool {
        let normalizedNeedle = normalize(needle)
        guard !normalizedNeedle.isEmpty else { return false }
        return normalize(haystack).contains(normalizedNeedle)
    }

    public static func firstDifferenceIndex(_ lhs: String, _ rhs: String) -> String.Index? {
        var left = lhs.startIndex
        var right = rhs.startIndex
        while left < lhs.endIndex && right < rhs.endIndex {
            if lhs[left] != rhs[right] { return left }
            left = lhs.index(after: left)
            right = rhs.index(after: right)
        }
        return left < lhs.endIndex ? left : nil
    }
}
