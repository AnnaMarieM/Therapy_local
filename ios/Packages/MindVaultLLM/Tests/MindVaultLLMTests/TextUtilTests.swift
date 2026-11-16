import XCTest
@testable import MindVaultLLM

/// The Kotlin text rules the ported parsers depend on (review should #9), and the
/// UTF-8 tail test the token streamer uses.
final class TextUtilTests: XCTestCase {
    // MARK: - Kotlin `String.trim()`: every code point <= 0x20, and no NBSP

    func testKtTrimStripsKotlinsWhitespace() {
        XCTAssertEqual(TextUtil.ktTrim("  text  "), "text")
        XCTAssertEqual(TextUtil.ktTrim("\t\n\rtext\u{000B}\u{000C}"), "text")
        XCTAssertEqual(TextUtil.ktTrim("\u{0000}text\u{0000}"), "text")  // Kotlin: '\u0000' <= ' '
        XCTAssertEqual(TextUtil.ktTrim("text"), "text")
        XCTAssertEqual(TextUtil.ktTrim(""), "")
        XCTAssertEqual(TextUtil.ktTrim(" \n\t "), "")
        XCTAssertEqual(TextUtil.ktTrim("a b"), "a b")  // inner whitespace is kept
    }

    func testKtTrimKeepsAnNbspLikeKotlin() {
        // Kotlin `trim()` works on `Char <= ' '`; the NBSP is U+00A0, so Android KEEPS
        // it. `CharacterSet.whitespaces` / `.whitespacesAndNewlines` would eat it —
        // that divergence is exactly what the review reproduced.
        XCTAssertEqual(TextUtil.ktTrim("\u{00A0}text\u{00A0}"), "\u{00A0}text\u{00A0}")
        XCTAssertEqual(TextUtil.ktTrim("\u{00A0}"), "\u{00A0}")
        // Deleting an NBSP role suffix instead would silently change the speaker.
        XCTAssertEqual(TextUtil.ktTrim("\npatient\t"), "patient")
    }

    func testKtTrimKeepsOtherUnicodeSpaces() {
        // U+2003 em space and U+3000 ideographic space are > 0x20: Kotlin keeps them.
        XCTAssertEqual(TextUtil.ktTrim("\u{2003}x\u{2003}"), "\u{2003}x\u{2003}")
        XCTAssertEqual(TextUtil.ktTrim("\u{3000}x"), "\u{3000}x")
    }

    func testIsBlankFollowsKotlin() {
        XCTAssertTrue(TextUtil.isBlank(""))
        XCTAssertTrue(TextUtil.isBlank(" \n\t"))
        XCTAssertTrue(TextUtil.isBlank("\u{001C}\u{001F}"))  // C0 separators: whitespace to Java
        // java.lang.Character.isWhitespace excludes the non-breaking spaces, so Kotlin's
        // `isBlank` answers false for an NBSP-only string (Swift's Unicode property would
        // answer true) — the scope fallback must not fire on it.
        XCTAssertFalse(TextUtil.isBlank("\u{00A0}"))
        XCTAssertFalse(TextUtil.isBlank("\u{2007}"))
        XCTAssertFalse(TextUtil.isBlank("\u{202F}"))
        XCTAssertFalse(TextUtil.isBlank("x"))
    }

    // MARK: - The call sites that used `CharacterSet.whitespaces`

    func testJsonStringIsNotTrimmed() {
        // org.json `optString` hands the text back verbatim (review should #9).
        let v = JSONValue.object(["k": .string(" patient\n")])
        XCTAssertEqual(v.string("k"), " patient\n")
        XCTAssertEqual(v.string("missing"), "")
    }

    func testSpeakerMapsDecodeTrimsARoleSuffixLikeAndroid() {
        // SpeakerMaps.kt: `o.optString(k).trim().lowercase()`, Kotlin trim.
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"patient\\n\"}"), ["S0": Role.patient])
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"THERAPIST\\t\"}"), ["S0": Role.therapist])
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"\\u000Bother\\u000C\"}"), ["S0": Role.other])
        // A name the enum does not know is `other`, and so is an NBSP-wrapped name
        // (Kotlin keeps the NBSP, so the lookup fails there too).
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"narrator\"}"), ["S0": Role.other])
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"\u{00A0}patient\"}"), ["S0": Role.other])
    }

    // MARK: - Incomplete UTF-8 tails (what the token streamer may hold back)

    func testAnIncompleteTailIsRecognisedEvenMidCharacter() {
        let e2 = Array("€".utf8)  // [0xE2, 0x82, 0xAC]
        XCTAssertEqual(e2.count, 3)
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail([]))
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail([e2[0]]))
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail([e2[0], e2[1]]))  // last byte is a continuation
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail(e2))             // complete

        let emoji = Array("😀".utf8)  // [0xF0, 0x9F, 0x98, 0x80]
        XCTAssertEqual(emoji.count, 4)
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail([emoji[0]]))
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail(Array(emoji.prefix(2))))
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail(Array(emoji.prefix(3))))
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail(emoji))

        let nbsp = Array("\u{00A0}".utf8)  // two-byte sequence
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail([nbsp[0]]))
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail(nbsp))

        // A lead byte preceded by plain ASCII is still a tail to hold.
        XCTAssertTrue(TextUtil.isIncompleteUTF8Tail(Array("a".utf8) + [e2[0]]))
    }

    func testAProvablyBrokenTailIsNotHeldBack() {
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail([0xFF]))          // never a lead
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail([0xC0, 0x80]))    // overlong form
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail([0xF5]))          // beyond U+10FFFF
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail([0x80, 0x80]))    // continuations only
        XCTAssertFalse(TextUtil.isIncompleteUTF8Tail(Array("text".utf8)))
    }
}
