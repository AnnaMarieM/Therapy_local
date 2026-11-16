import XCTest
@testable import MindVaultLLM

/// The resource loader: the prompt files ship inside the package, the Czech ones from the
/// cs/ folder, trimmed like Android's MemoryFiles.prompt.
final class PromptStoreTests: XCTestCase {
    func testLoadsAnEnglishPrompt() throws {
        let text = try PromptStore.load(Prompts.EXTRACT_USER, PromptLanguage.EN)
        XCTAssertFalse(text.isEmpty)
        XCTAssertTrue(text.contains("Client: {{CLIENT}}"))
        // The Android loader trims: the file ends with blank lines.
        XCTAssertFalse(text.hasSuffix("\n"))
        XCTAssertFalse(text.hasPrefix(" "))
    }

    func testLoadsACzechPromptFromTheCsFolder() throws {
        let cs = try PromptStore.load(Prompts.EXTRACT_USER, PromptLanguage.CS)
        let en = try PromptStore.load(Prompts.EXTRACT_USER, PromptLanguage.EN)
        XCTAssertFalse(cs.isEmpty)
        XCTAssertNotEqual(cs, en)
        XCTAssertTrue(cs.contains("Klient: {{CLIENT}}"))
    }

    func testLoadsByRelativePath() throws {
        XCTAssertEqual(
            try PromptStore.load(Prompts.named(Prompts.EXTRACT_SYSTEM, PromptLanguage.CS)),
            try PromptStore.load(Prompts.EXTRACT_SYSTEM, PromptLanguage.CS)
        )
    }

    func testEveryShippedPromptLoadsInBothLanguages() throws {
        let shipped = [
            Prompts.EXTRACT_SYSTEM, Prompts.EXTRACT_USER, Prompts.REVIEW_SYSTEM, Prompts.REVIEW_USER,
            Prompts.ROLES_SYSTEM,
            "summary.system.txt", "summary.user.txt", "summary.birp.system.txt", "summary.dap.system.txt",
            "summary.narrative.system.txt", "summary.notes.system.txt", "summary.notes.user.txt",
        ]
        for name in shipped {
            for language in [PromptLanguage.EN, PromptLanguage.CS] {
                let text = try PromptStore.load(name, language)
                XCTAssertFalse(text.isEmpty, "\(language)/\(name)")
            }
        }
        // Translation is app-side: those two prompts are not shipped here.
        XCTAssertThrowsError(try PromptStore.load(Prompts.TRANSLATE_SYSTEM, PromptLanguage.EN))
    }

    func testMissingAndMalformedNamesThrow() {
        XCTAssertThrowsError(try PromptStore.load("no.such.txt", PromptLanguage.EN)) { error in
            XCTAssertEqual(error as? PromptStore.StoreError, .notFound("no.such.txt"))
        }
        XCTAssertThrowsError(try PromptStore.load("extract.system"))     // no extension
        XCTAssertThrowsError(try PromptStore.load("", PromptLanguage.EN))
        XCTAssertThrowsError(try PromptStore.load("cs/", PromptLanguage.EN))
    }
}
