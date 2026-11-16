import XCTest
@testable import MindVaultLLM

/// Which language the extraction and review prompts are in. Ported from
/// PromptLanguageTest.kt.
final class PromptLanguageTests: XCTestCase {
    func testCzechAppAndCzechClientUseCzechPrompts() {
        XCTAssertEqual(PromptLanguage.choose("cs", "cs"), PromptLanguage.CS)
    }

    func testAnEnglishAppKeepsEnglishPromptsEvenForACzechClient() {
        XCTAssertEqual(PromptLanguage.choose("en", "cs"), PromptLanguage.EN)
    }

    func testACzechAppWithAnEnglishClientKeepsEnglishPrompts() {
        XCTAssertEqual(PromptLanguage.choose("cs", "en"), PromptLanguage.EN)
    }

    func testTheLanguageTagsAreCaseInsensitive() {
        XCTAssertEqual(PromptLanguage.choose("CS", "CS"), PromptLanguage.CS)
        XCTAssertEqual(PromptLanguage.choose("Cs", "cs"), PromptLanguage.CS)
        XCTAssertEqual(PromptLanguage.choose("en", "EN"), PromptLanguage.EN)
    }
}
