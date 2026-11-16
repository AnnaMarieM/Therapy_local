import Foundation

/// Small text helpers that reproduce the exact semantics of the Kotlin code this
/// package was ported from (app/src/main/java/com/therapylocal/app/**), so the
/// frozen prompts and parsers behave the same on iOS as on Android.
/// Kotlin counts characters as UTF-16 code units (`String.length`), trims
/// characters with code point <= 0x20 (`String.trim`), and splits on the regex
/// `\s+` (ASCII whitespace only). Internal to the package; tests use `@testable`.
/// Only the two helpers the engine target (`MindVaultLLMLlama`) needs are public —
/// `ktTrim` for the rejected-template message, `isIncompleteUTF8Tail` for the token
/// streamer — because that target is a separate module.
public enum TextUtil {
    /// Kotlin `String.trim()`: drops leading/trailing characters whose code point is <= 0x20.
    public static func ktTrim(_ s: String) -> String {
        var scalars = Array(s.unicodeScalars)
        while let first = scalars.first, first.value <= 0x20 { scalars.removeFirst() }
        while let last = scalars.last, last.value <= 0x20 { scalars.removeLast() }
        return String(String.UnicodeScalarView(scalars))
    }

    /// Whether `bytes` ends in an INCOMPLETE multi-byte UTF-8 sequence — a lead byte
    /// that is still missing some of its continuation bytes — so more bytes could
    /// still turn the whole buffer into valid UTF-8 (worth holding back, as a streamed
    /// token piece can split a character). False when the tail is already provably
    /// invalid or plainly complete: then a failed decode will never be fixed by the
    /// next piece and the buffer can flush lossily instead of stalling the stream.
    ///
    /// The lead is found by walking BACK over the trailing continuation bytes — the
    /// last byte alone is not the lead when the buffer is cut mid-character
    /// (`[0xE2, 0x82]` ends in a continuation byte and is still incomplete).
    public static func isIncompleteUTF8Tail(_ bytes: [UInt8]) -> Bool {
        var i = bytes.count - 1
        var trailing = 0
        while i >= 0, 0x80...0xBF ~= bytes[i] {
            trailing += 1
            i -= 1
        }
        guard i >= 0 else { return false }  // only continuation bytes: nothing can lead them
        // Only the lead byte of a well-formed multi-byte sequence can be completed.
        // (0xC0/0xC1 and 0xF5-0xFF never start valid UTF-8, so they are "invalid".)
        let needed: Int
        switch bytes[i] {
        case 0xC2...0xDF: needed = 1
        case 0xE0...0xEF: needed = 2
        case 0xF0...0xF4: needed = 3
        default: return false
        }
        return trailing < needed
    }

    /// Kotlin `String.isBlank()`: true when there is no character that is not whitespace.
    static func isBlank(_ s: String) -> Bool {
        s.unicodeScalars.allSatisfy(isWhitespaceScalar)
    }

    /// Kotlin `Char.isWhitespace()` — on the JVM/Android that is
    /// `java.lang.Character.isWhitespace`: the Zs separators EXCEPT the non-breaking
    /// ones (NBSP U+00A0, figure space U+2007, narrow NBSP U+202F), plus tab, LF, VT,
    /// FF, CR and the four C0 separators U+001C…U+001F. (Swift's
    /// `scalar.properties.isWhitespace` is the Unicode White_Space property, which
    /// treats NBSP as whitespace — Kotlin does not, so `" \u{00A0}"` is not blank.)
    static func isWhitespaceScalar(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x09...0x0D, 0x1C...0x1F: return true
        case 0x00A0, 0x2007, 0x202F: return false
        default: return c.properties.generalCategory == .spaceSeparator
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
