import XCTest
@testable import MindVaultLLM

/// The Swift Gemma 4 renderer against the pinned model's own template.
///
/// Every expected string here is the output of rendering `Fixtures/gemma4-chat-template.txt`
/// (the pinned GGUF's `tokenizer.chat_template`, see `Fixtures/README.md`) with Jinja2
/// 3.1.6 for the same message list — not a hand-written guess. Two settled details:
/// the template's leading `{{ bos_token }}` is dropped (the tokenizer adds BOS, and
/// `<bos>` in the text would be parsed as a second one), and consecutive assistant
/// messages concatenate into one model turn with no separator.
final class Gemma4ChatTemplateTests: XCTestCase {
    private var raw: String { TestFixtures.gemma4ChatTemplate }

    func testTheFixtureIsTheTemplateStoredInThePinnedGguf() {
        XCTAssertEqual(raw.utf8.count, TestFixtures.gemma4ChatTemplateBytes)
        XCTAssertEqual(SHA256.hex(raw), TestFixtures.gemma4ChatTemplateSHA256)
        XCTAssertTrue(raw.hasPrefix("{#\n  Template: Google Gemma 4 Canonical Chat Template"))
    }

    func testThePinnedTemplateCarriesTheGemma4Markers() {
        XCTAssertNotNil(Gemma4ChatTemplate.markers(in: raw))
    }

    // MARK: - The app's two-message shape (what `ask` and both proofs render)

    func testSystemAndUserTurnIsByteExact() {
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(system: "SYS", user: "USR"),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\n"
        )
    }

    func testAnEmptySystemMessageStillOpensItsTurn() {
        // The template's setup block fires on the ROLE, not on the content.
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(system: "", user: "USR"),
            "<|turn>system\n<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\n"
        )
    }

    func testContentIsTrimmedWithJinjasRule() {
        // Jinja `| trim` = Python `str.strip`: newlines, tabs, spaces AND the NBSP.
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(system: "  SYS \n", user: "\nUSR\t"),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\n"
        )
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(system: "SYS", user: "\u{00A0}USR\u{00A0}"),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\n"
        )
    }

    // MARK: - Whole turn lists

    func testAUserOnlyListOmitsTheSystemTurn() {
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt([.init(role: "user", content: "USR")], addGenerationPrompt: true),
            "<|turn>user\nUSR<turn|>\n<|turn>model\n"
        )
    }

    func testConsecutiveAssistantMessagesAreOneModelTurn() {
        let messages = [
            Gemma4ChatTemplate.Message(role: "system", content: "SYS"),
            Gemma4ChatTemplate.Message(role: "user", content: "USR"),
            Gemma4ChatTemplate.Message(role: "assistant", content: "A1"),
            Gemma4ChatTemplate.Message(role: "assistant", content: "A2"),
        ]
        // One opener, one closing marker, no separator between the two halves.
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(messages, addGenerationPrompt: true),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\nA1A2<turn|>\n<|turn>model\n"
        )
    }

    func testWithoutAGenerationPromptNothingIsAppended() {
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt([.init(role: "system", content: "SYS"), .init(role: "user", content: "USR")], addGenerationPrompt: false),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n"
        )
    }

    func testAFinalAssistantTurnStillCloses() {
        let messages = [
            Gemma4ChatTemplate.Message(role: "system", content: "SYS"),
            Gemma4ChatTemplate.Message(role: "user", content: "USR"),
            Gemma4ChatTemplate.Message(role: "assistant", content: "A"),
        ]
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(messages, addGenerationPrompt: false),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\nA<turn|>\n"
        )
    }

    // MARK: - Thinking channels (the template's `strip_thinking`)

    func testModelThinkingChannelsAreDropped() {
        let messages = [
            Gemma4ChatTemplate.Message(role: "system", content: "SYS"),
            Gemma4ChatTemplate.Message(role: "user", content: "USR"),
            Gemma4ChatTemplate.Message(role: "assistant", content: "<|channel>thought\nplan\n<channel|>Reply"),
        ]
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(messages, addGenerationPrompt: true),
            "<|turn>system\nSYS<turn|>\n<|turn>user\nUSR<turn|>\n<|turn>model\nReply<turn|>\n<|turn>model\n"
        )
        // A piece without an opener is kept (the template concatenates the parts).
        XCTAssertEqual(Gemma4ChatTemplate.stripThinking("A<channel|>B"), "AB")
        // Surrounding whitespace on both sides of a dropped channel is trimmed away.
        XCTAssertEqual(Gemma4ChatTemplate.jinjaTrim(Gemma4ChatTemplate.stripThinking("  <|channel>t\n<channel|>  Answer  ")), "Answer")
    }

    func testUserContentKeepsChannelMarkersItMightQuote() {
        // Only the model role goes through `strip_thinking`; a user turn quoting the
        // marker keeps it (the template guards the macro with `if role == 'model'`).
        XCTAssertEqual(
            Gemma4ChatTemplate.prompt(system: "SYS", user: "<|channel>x<channel|>"),
            "<|turn>system\nSYS<turn|>\n<|turn>user\n<|channel>x<channel|><turn|>\n<|turn>model\n"
        )
    }

    // MARK: - Trims and gates

    func testKtTrimKeepsAnNbspWhereJinjasTrimDropsIt() {
        // Two different trims on purpose: the parser reproduces Kotlin `trim()`
        // (code points <= 0x20, NBSP kept), the renderer reproduces Jinja's `| trim`.
        XCTAssertEqual(TextUtil.ktTrim("\u{00A0} text \u{00A0}"), "\u{00A0} text \u{00A0}")
        XCTAssertEqual(Gemma4ChatTemplate.jinjaTrim("\u{00A0} text \u{00A0}"), "text")
    }

    func testTheMarkerGateRejectsTemplatesWithoutTheTurnFormat() {
        XCTAssertNil(Gemma4ChatTemplate.markers(in: "gemma"))
        XCTAssertNil(Gemma4ChatTemplate.markers(in: "{% for m in messages %}<start_of_turn>{{ m['role'] }}\n{{ m['content'] }}<end_of_turn>\n{% endfor %}"))
        XCTAssertNil(Gemma4ChatTemplate.markers(in: "<|turn>system\n{{ x }}<turn|>\n"))  // no role composition, no model opener
        XCTAssertNil(Gemma4ChatTemplate.markers(in: ""))
    }

    func testTheMarkerGateAcceptsThePinnedTemplateBothWaysOfSpellingTheNewline() {
        XCTAssertNotNil(Gemma4ChatTemplate.markers(in: raw))
        // The same template with the Jinja escape already resolved to real newlines.
        let resolved = raw
            .replacingOccurrences(of: "'<|turn>system\\n'", with: "'<|turn>system\n'")
            .replacingOccurrences(of: "'<|turn>model\\n'", with: "'<|turn>model\n'")
        XCTAssertNotEqual(resolved, raw)
        XCTAssertNotNil(Gemma4ChatTemplate.markers(in: resolved))
    }
}
