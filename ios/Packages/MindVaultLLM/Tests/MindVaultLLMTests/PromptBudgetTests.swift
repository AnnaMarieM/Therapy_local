import XCTest
@testable import MindVaultLLM

/// The token estimate and the context sizes. Ported from PromptBudgetTest.kt
/// (the same made-up Czech sentence, the same measured numbers).
final class PromptBudgetTests: XCTestCase {
    // Measured on the phone with Gemma 4: 714 Czech words of the made-up transcript plus
    // the app's extraction prompts were 1,793 tokens.
    private let czech = String(repeating: "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené. ", count: 40)

    func testCzechIsEstimatedHigherThanEnglishForTheSameLength() {
        let english = String(repeating: "My sister Lucie moved to Brno and I feel that we are now terribly distant from each other. ", count: 40)
        XCTAssertGreaterThan(PromptBudget.tokens(czech), PromptBudget.tokens(english))
    }

    func testTheEstimateIsNeverBelowTheMeasuredTokenCount() {
        // 5,401 characters measured as 1,793 tokens (3.0 per token overall, 2.7 for the
        // Czech text): the estimate must not be below. Kotlin takes 5,401 UTF-16 units
        // (`czech.repeat(2).take(5401)`); sliced on code units for the same measure.
        let doubled = String(repeating: czech, count: 2)
        let text = String(decoding: doubled.utf16.prefix(5401), as: UTF16.self)
        XCTAssertEqual(text.count, 5401)
        XCTAssertGreaterThanOrEqual(PromptBudget.tokens(text), 1793)
    }

    func testAccentedDetectionFollowsTheKotlinRule() {
        // At least 1% non-ASCII letters flags the language; plain English stays below.
        XCTAssertTrue(PromptBudget.accented(czech))
        XCTAssertFalse(PromptBudget.accented("Plain english transcript, no accents at all."))
        // Exactly 1%: one accented letter in 100 → flagged.
        XCTAssertTrue(PromptBudget.accented(String(repeating: "a", count: 99) + "ě"))
        // No letters at all → not accented.
        XCTAssertFalse(PromptBudget.accented("123 456"))
    }

    func testTokensCountUtf16UnitsLikeKotlinLength() {
        // 6 letters + one emoji (2 UTF-16 units): Kotlin length is 9, plain → ceil(9/3.5) = 3.
        XCTAssertEqual(PromptBudget.tokens("abcdef😀"), 3)
        // Accented text estimates higher per character.
        XCTAssertGreaterThan(PromptBudget.tokens("ěěěěěěěě"), PromptBudget.tokens("eeeeeeee"))
    }

    func testAccentedTokensIsTheCeilOnTheAccentedRate() {
        XCTAssertEqual(PromptBudget.accentedTokens(100), 42)   // ceil(100/2.4)
        XCTAssertEqual(PromptBudget.accentedTokens(0), 0)
        XCTAssertEqual(PromptBudget.accentedTokens(1), 1)
        XCTAssertEqual(PromptBudget.accentedTokens(24), 10)    // ceil(10.0) = 10
        XCTAssertEqual(PromptBudget.accentedTokens(25), 11)
    }

    func testTheReplyAndTheTemplateAreCounted() {
        let needed = PromptBudget.required("system", "user", 1600)
        XCTAssertGreaterThan(needed, 1600 + 96)
    }

    func testContextIsRoundedUpAndNeverBelowTheFloor() {
        XCTAssertEqual(6144, PromptBudget.contextFor(3000, 6144))
        XCTAssertEqual(6656, PromptBudget.contextFor(6200, 6144))
        XCTAssertEqual(6144, PromptBudget.contextFor(6144, 6144))
    }

    func testContextIsCappedAtTheLargestTheAppAsksFor() {
        XCTAssertEqual(PromptBudget.MAX_CONTEXT, PromptBudget.contextFor(1_000_000, 6144))
        XCTAssertLessThanOrEqual(PromptBudget.MAX_CONTEXT, 16384)
    }

    func testARealWindowOfCzechFitsTheFloorWithItsReply() {
        // 1000 window tokens at the app's 1.4 per word are 714 words, and Czech runs about
        // 2 tokens per word: the real prompt is close to 1,800 tokens.
        let window = String(repeating: "ě", count: 714 * 7)
        let needed = PromptBudget.required(String(repeating: "s", count: 1200), window, 1600)
        XCTAssertGreaterThan(needed, 3500, "needs \(needed)")
        XCTAssertLessThan(needed, 6144, "needs \(needed)")
    }
}
