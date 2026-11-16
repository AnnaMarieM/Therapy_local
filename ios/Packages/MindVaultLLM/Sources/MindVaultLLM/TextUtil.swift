import Foundation

/// Small text helpers that reproduce the exact semantics of the Kotlin code this
/// package was ported from (app/src/main/java/com/therapylocal/app/**), so the
/// frozen prompts and parsers behave the same on iOS as on Android.
/// Kotlin counts characters as UTF-16 code units (`String.length`), trims
/// characters with code point <= 0x20 (`String.trim`), and splits on the regex
/// `\s+` (ASCII whitespace only). Internal to the package; tests use `@testable`.
enum TextUtil {
    /// Kotlin `String.trim()`: drops leading/trailing characters whose code point is <= 0x20.
    static func ktTrim(_ s: String) -> String {
        var scalars = Array(s.unicodeScalars)
        while let first = scalars.first, first.value <= 0x20 { scalars.removeFirst() }
        while let last = scalars.last, last.value <= 0x20 { scalars.removeLast() }
        return String(String.UnicodeScalarView(scalars))
    }

    /// Kotlin `String.isBlank()`: true when there is no character that is not whitespace.
    static func isBlank(_ s: String) -> Bool {
        s.unicodeScalars.allSatisfy(isWhitespaceScalar)
    }

    /// Kotlin `Char.isWhitespace` (approximated with the Unicode whitespace property,
    /// which agrees on everything the ported code feeds it: spaces, tabs, newlines).
    static func isWhitespaceScalar(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20: return true
        default: return c.properties.isWhitespace
        }
    }

    static func isWhitespaceCharacter(_ c: Character) -> Bool {
        c.unicodeScalars.allSatisfy(isWhitespaceScalar)
    }

    /// Kotlin `String.split(Regex("\\s+"))` with the empty pieces removed.
    static func splitWhitespace(_ s: String) -> [String] {
        s.split(omittingEmptySubsequences: true, whereSeparator: isWhitespaceCharacter).map(String.init)
    }

    /// Kotlin `String.lines()`: splits on `\r\n`, `\n` or `\r`, keeping empty lines
    /// (a trailing line break yields a final empty line).
    static func lines(_ s: String) -> [String] {
        var out: [String] = []
        var current = ""
        let scalars = Array(s.unicodeScalars)
        var i = 0
        while i < scalars.count {
            let c = scalars[i]
            if c == "\n" {
                out.append(current)
                current = ""
                i += 1
            } else if c == "\r" {
                out.append(current)
                current = ""
                i += 1
                if i < scalars.count, scalars[i] == "\n" { i += 1 }
            } else {
                current.unicodeScalars.append(c)
                i += 1
            }
        }
        out.append(current)
        return out
    }

    /// Kotlin `Regex("\\p{M}+")`-style replacement through NSRegularExpression (ICU,
    /// so `\p{M}`, `\b` and `[\p{L}\p{N}]` behave like the JVM engine).
    static func regexReplace(_ s: String, _ pattern: String, _ template: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        let range = NSRange(s.startIndex..., in: s)
        return re.stringByReplacingMatches(in: s, range: range, withTemplate: template)
    }

    /// All matches of `pattern` in `s` (Kotlin `Regex.findAll`), each as its captured groups.
    static func regexMatches(_ s: String, _ pattern: String) -> [[String?]] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let range = NSRange(s.startIndex..., in: s)
        return re.matches(in: s, range: range).map { m in
            (0..<m.numberOfRanges).map { r in Range(m.range(at: r), in: s).map { String(s[$0]) } }
        }
    }

    /// First index of `needle` in `hay` as a UTF-16 offset (Kotlin `String.indexOf`,
    /// which is also UTF-16 based); nil when absent.
    static func utf16IndexOf(_ hay: [UInt16], _ needle: [UInt16]) -> Int? {
        if needle.isEmpty { return 0 }
        if needle.count > hay.count { return nil }
        let last = hay.count - needle.count
        var i = 0
        while i <= last {
            if hay[i] == needle[0] {
                var j = 1
                while j < needle.count, hay[i + j] == needle[j] { j += 1 }
                if j == needle.count { return i }
            }
            i += 1
        }
        return nil
    }

    /// UTF-16 offset of the last occurrence of `needle` in `hay` (Kotlin `lastIndexOf`).
    static func utf16LastIndexOf(_ hay: [UInt16], _ needle: [UInt16]) -> Int? {
        if needle.isEmpty { return hay.count }
        if needle.count > hay.count { return nil }
        var i = hay.count - needle.count
        while i >= 0 {
            if hay[i] == needle[0] {
                var j = 1
                while j < needle.count, hay[i + j] == needle[j] { j += 1 }
                if j == needle.count { return i }
            }
            i -= 1
        }
        return nil
    }

    /// Kotlin `String.contains(substring)` on the UTF-16 view.
    static func contains(_ hay: String, _ needle: String) -> Bool {
        if needle.isEmpty { return true }
        return utf16IndexOf(Array(hay.utf16), Array(needle.utf16)) != nil
    }

    /// Escapes a string for use inside an ICU regular expression (Kotlin `Regex.escape`).
    static func regexEscape(_ s: String) -> String {
        s.unicodeScalars.map { c -> String in
            let letter = c.properties.isAlphabetic || c.properties.numericType != nil
            return letter ? String(c) : "\\\(String(c))"
        }.joined()
    }
}
