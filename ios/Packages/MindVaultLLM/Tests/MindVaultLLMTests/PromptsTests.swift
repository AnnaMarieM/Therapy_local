import XCTest
@testable import MindVaultLLM

/// The prompt templates: placeholder filling, the version hash and the language paths.
/// Ported from the Prompts part of Facts.kt and PromptLanguageTest.kt.
final class PromptsTests: XCTestCase {
    func testFillReplacesEveryPlaceholder() {
        let out = Prompts.fill("Client: {{CLIENT}}\nDate: {{DATE}}", ["DATE": "2026-11-16", "CLIENT": "Jana"])
        XCTAssertEqual(out, "Client: Jana\nDate: 2026-11-16")
    }

    /// A key the map does not carry keeps its `{{NAME}}` (the Android `fill` replaces
    /// only the entries it gets — a half-filled template stays visible, not blank).
    func testFillKeepsThePlaceholderOfAMissingKey() {
        let out = Prompts.fill("{{A}} {{MISSING}} {{B}}", ["A": "x", "B": "y"])
        XCTAssertEqual(out, "x {{MISSING}} y")
    }

    func testFillIsDeterministic() {
        let one = Prompts.fill("{{A}}{{B}}", ["A": "1", "B": "2"])
        let two = Prompts.fill("{{A}}{{B}}", ["B": "2", "A": "1"])
        XCTAssertEqual(one, two)
    }

    func testVersionIsTwelveHexCharsAndChangesWithTheText() {
        let v1 = Prompts.version("a", "b")
        XCTAssertEqual(v1.count, 12)
        XCTAssertTrue(v1.allSatisfy { "0123456789abcdef".contains($0) })
        XCTAssertEqual(Prompts.version("a", "b"), Prompts.version(["a", "b"]))
        XCTAssertNotEqual(Prompts.version("a", "b"), Prompts.version("a", "b "))
        XCTAssertNotEqual(Prompts.version("a", "b"), Prompts.version("b", "a"))
    }

    /// SHA-256 over length-prefixed parts: different splits of the same characters never
    /// collide ("ab"+"c" vs "a"+"bc").
    func testHashLengthPrefixesTheParts() {
        XCTAssertEqual(Prompts.hash("ab", "c"), Prompts.hash("ab", "c"))
        XCTAssertNotEqual(Prompts.hash("ab", "c"), Prompts.hash("a", "bc"))
        XCTAssertNotEqual(Prompts.hash("a", "bc"), Prompts.hash("abc"))
        // The empty list hashes like an empty input, not like the empty string.
        XCTAssertNotEqual(Prompts.hash(), Prompts.hash(""))
        // Multi-byte characters count their UTF-8 bytes.
        XCTAssertEqual(Prompts.hash("ě").count, 64)
        XCTAssertNotEqual(Prompts.hash("ě"), Prompts.hash("e"))
    }

    func testNamedBuildsTheCsPath() {
        XCTAssertEqual(Prompts.named(Prompts.EXTRACT_SYSTEM, PromptLanguage.CS), "cs/extract.system.txt")
        XCTAssertEqual(Prompts.named(Prompts.EXTRACT_SYSTEM, PromptLanguage.EN), "extract.system.txt")
    }

    /// The shipped English files and their Czech copies ask for the same placeholders
    /// (the parsers read exactly those keys). Ported from PromptLanguageTest
    /// (which reads demo/prompts — here the packaged copies are checked).
    func testCzechPromptsKeepThePlaceholdersOfTheEnglishOnes() throws {
        let placeholder = try NSRegularExpression(pattern: "\\{\\{([A-Z]+)\\}\\}")
        func names(_ text: String) -> Set<String> {
            let range = NSRange(text.startIndex..., in: text)
            return Set(placeholder.matches(in: text, range: range).compactMap {
                Range($0.range(at: 1), in: text).map { String(text[$0]) }
            })
        }
        for name in [Prompts.EXTRACT_SYSTEM, Prompts.EXTRACT_USER, Prompts.REVIEW_SYSTEM, Prompts.REVIEW_USER] {
            let en = try PromptStore.load(name, PromptLanguage.EN)
            let cs = try PromptStore.load(name, PromptLanguage.CS)
            XCTAssertEqual(names(en), names(cs), name)
        }
        // The parsers rely on these keys, so the Czech prompts must ask for exactly them.
        let extract = try PromptStore.load(Prompts.EXTRACT_SYSTEM, PromptLanguage.CS)
        for key in ["\"heading\"", "\"content\"", "\"evidence\""] {
            XCTAssertTrue(extract.contains(key), key)
        }
        let review = try PromptStore.load(Prompts.REVIEW_SYSTEM, PromptLanguage.CS)
        for key in ["\"duplicate\"", "\"of\""] {
            XCTAssertTrue(review.contains(key), key)
        }
    }
}
