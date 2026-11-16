import XCTest
@testable import MindVaultLLM

/// The decision `LlamaRunner.open` makes about a loaded model's template.
///
/// The probe (`llama_chat_apply_template` returning >= 0) is injected, so the
/// decision is tested on Linux, where the pinned llama binary cannot be linked. The
/// premise that matters — the pinned Gemma 4 template and a foreign template are
/// both UNKNOWN to llama.cpp b11960 — is checked two ways: against the real template
/// fixture (`markers(in:)`) and against the complete list of fingerprint literals
/// below, which is every `tmpl_contains(...)` haystack of `llm_chat_detect_template`
/// (`src/llama-chat.cpp:89-215`, commit 8642541) with its C++ escapes resolved, plus
/// the one `tmpl.find("mistral") == 0` check. Empirically, on libllama built from the
/// same commit, the real template and a fake one both return -1 while `"gemma"` and
/// `"chatml"` apply (`/tmp` probe, PR #131).
final class ChatTemplatePlanTests: XCTestCase {
    /// A template with none of the markers and none of the fingerprint literals — the
    /// shape a new model family ships.
    static let foreignTemplate = """
        {# template for a model family llama.cpp has never heard of #}
        {%- for message in messages -%}<<{{ message['role'] }}>>{{ message['content'] }}{%- endfor -%}
        {{- '<|answer>' -}}
        """

    /// The same shape in Gemma 4's own marker vocabulary, but not the pinned text.
    static let syntheticGemma4Template = """
        {%- if messages[0]['role'] == 'system' -%}{{- '<|turn>system\\n' + messages[0]['content'] + '<turn|>\\n' -}}{%- endif -%}
        {%- for message in messages -%}
        {{- '<|turn>' + role + '\\n' -}}{{- message['content'] -}}{{- '<turn|>\\n' -}}
        {%- endfor -%}
        {{- '<|turn>model\\n' -}}
        """

    static let fingerprintLiterals: [String] = [
        "<用户>",
        "<｜Assistant｜>",
        "<｜User｜>",
        "<｜end▁of▁sentence｜>",
        "[unused9]系统：[unused10]",
        "<|im_start|>",
        "<|im_sep|>",
        "<end_of_utterance>",
        "[INST]",
        "[SYSTEM_PROMPT]",
        "' [INST] ' + system_message",
        "[AVAILABLE_TOOLS]",
        " [INST]",
        "\"[INST]\"",
        "<<SYS>>",
        "bos_token + '[INST]",
        "content.strip()",
        "<|assistant|>",
        "<|end|>",
        "[gMASK]<sop>",
        "<|user|>",
        "<|tool_declare|>",
        "</s>",
        "<|{{ item['role'] }}|>",
        "<|begin_of_image|>",
        "<|endoftext|>",
        "bos_token + message['role']",
        "<start_of_turn>",
        "'\\n\\nAssistant: ' + eos_token",
        "GPT4 Correct ",
        "USER: ",
        "ASSISTANT: ",
        "SYSTEM: ",
        "### Instruction:",
        "<|EOT|>",
        "<|START_OF_TURN_TOKEN|>",
        "<|USER_TOKEN|>",
        "<|start_header_id|>",
        "<|end_header_id|>",
        "[gMASK]sop",
        "'Assistant: ' + message['content'] + eos_token",
        "[|system|]",
        "[|assistant|]",
        "[|endofturn|]",
        "[|tool|]",
        "rwkv-world",
        "{{- 'User: ' + message['content']|trim + '\\n\\n' -}}",
        "<|start_of_role|>",
        "<tool_call>",
        "<tools>",
        "g4_default_system_message",
        "message['role'] + additional_special_tokens[0] + message['content'] + additional_special_tokens[1]",
        "<|role_start|>",
        " Ассистент:",
        "<role>ASSISTANT</role>",
        "'HUMAN'",
        "\"HUMAN\"",
        "<think>",
        "<role>HUMAN</role>",
        "<|role_end|>",
        "<|header_start|>",
        "<|header_end|>",
        "<|endofuserprompt|>",
        "<|extra_0|>",
        "<|extra_4|>",
        "<|start|>",
        "<|channel|>",
        "<｜hy_Assistant｜>",
        "<｜hy_begin▁of▁sentence｜>",
        "<｜hy_place▁holder▁no▁3｜>",
        "<|im_assistant|>assistant<|im_middle|>",
        "<seed:bos>",
        "'Assistant: '  + message['content'] + '<|separator|>",
        "<|begin|>",
        "<|content|>",
    ]

    func testThePinnedTemplateMatchesNoFingerprintLiteral() {
        // Blockers 1's premise: 0 of llama.cpp b11960's haystacks occur in the real
        // template, so `llm_chat_detect_template` answers UNKNOWN and
        // `llama_chat_apply_template` returns -1.
        XCTAssertEqual(Self.fingerprintLiterals.count, 75)
        for literal in Self.fingerprintLiterals {
            XCTAssertFalse(TestFixtures.gemma4ChatTemplate.contains(literal), "pinned template contains \(literal)")
        }
        XCTAssertFalse(TestFixtures.gemma4ChatTemplate.hasPrefix("mistral"))
        XCTAssertEqual(llmLlamaWouldDetect(TestFixtures.gemma4ChatTemplate), .unknown)
    }

    func testTheForeignTemplateMatchesNoFingerprintLiteral() {
        for literal in Self.fingerprintLiterals {
            XCTAssertFalse(Self.foreignTemplate.contains(literal), "foreign template contains \(literal)")
        }
        XCTAssertFalse(Self.foreignTemplate.hasPrefix("mistral"))
        XCTAssertEqual(llmLlamaWouldDetect(Self.foreignTemplate), .unknown)
    }

    func testTheSyntheticGemma4TemplateIsAlsoUnknownToLlamaCpp() {
        // Same verdict as the real one, derived independently of the fixture.
        for literal in Self.fingerprintLiterals {
            XCTAssertFalse(Self.syntheticGemma4Template.contains(literal), "synthetic template contains \(literal)")
        }
        XCTAssertEqual(llmLlamaWouldDetect(Self.syntheticGemma4Template), .unknown)
    }

    // MARK: - The plan

    func testUnsupportedGemma4TemplateIsRenderedByTheSwiftBuilder() {
        var probed: [String] = []
        let plan = ChatTemplatePlan.plan(rawTemplate: TestFixtures.gemma4ChatTemplate) { template in
            probed.append(template)
            return false  // llama.cpp: -1, UNKNOWN
        }
        XCTAssertEqual(plan, .gemma4Swift)
        XCTAssertEqual(probed.count, 1)
        XCTAssertEqual(probed.first, TestFixtures.gemma4ChatTemplate)
    }

    func testUnsupportedSyntheticGemma4TemplateIsRenderedByTheSwiftBuilder() {
        let plan = ChatTemplatePlan.plan(rawTemplate: Self.syntheticGemma4Template) { _ in false }
        XCTAssertEqual(plan, .gemma4Swift)
    }

    func testUnsupportedForeignTemplateFallsBackToTheBuiltinGemmaName() {
        // Not Gemma 4 either: llama.cpp's built-in "gemma" is the last resort. It
        // always applies (its <start_of_turn> format), even though it is not the
        // format such a model was trained on — the alternative would be throwing on
        // every call.
        let plan = ChatTemplatePlan.plan(rawTemplate: Self.foreignTemplate) { _ in false }
        XCTAssertEqual(plan, .llamaApply("gemma"))
    }

    func testASupportedTemplateStaysWithLlamaCpp() {
        let chatml = "{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] }}<|im_end|>\n{% endfor %}"
        let plan = ChatTemplatePlan.plan(rawTemplate: chatml) { _ in true }
        XCTAssertEqual(plan, .llamaApply(chatml))
    }

    func testTheBuiltinNameItselfIsProbedNeverTrustedBlindly() {
        // The GGUF-without-template case hands "gemma" in; the plan still asks.
        var asked = false
        _ = ChatTemplatePlan.plan(rawTemplate: "gemma") { template in
            asked = true
            XCTAssertEqual(template, "gemma")
            return true
        }
        XCTAssertTrue(asked)
    }

    // MARK: - A local model of `llm_chat_detect_template`

    private enum Detection: Equatable {
        case unknown
        case named(String)
        /// One of the marker branches fired (exact family does not matter here).
        case literal(String)
    }

    /// b11960's exact-name map (`LLM_CHAT_TEMPLATES`, `src/llama-chat.cpp:40-86`).
    static let knownTemplateNames = [
        "llama2", "llama2-sys", "llama2-sys-bos", "llama2-sys-strip",
        "mistral-v1", "mistral-v3", "mistral-v3-tekken", "mistral-v7", "mistral-v7-tekken",
        "phi3", "phi4", "falcon3", "zephyr", "monarch", "gemma", "orion", "openchat",
        "vicuna", "vicuna-orca", "deepseek", "deepseek2", "deepseek3", "deepseek-ocr",
        "command-r", "llama3", "chatglm3", "chatglm4", "glmedge", "minicpm",
        "exaone3", "exaone4", "exaone-moe", "rwkv-world", "granite", "granite-4.0",
        "granite-4.1", "gigachat", "megrez", "yandex", "bailing", "bailing-think",
        "bailing2", "llama4", "smolvlm", "hunyuan-moe", "gpt-oss", "hunyuan-dense",
        "hunyuan-vl", "kimi-k2", "seed_oss", "grok-2", "pangu-embedded", "solar-open",
    ]

    /// A deliberately coarse re-implementation of b11960's detector: exact name, then
    /// the `mistral` prefix, then the marker list. Enough to show that a template
    /// reaching the marker list does so, and that these templates do not.
    private func llmLlamaWouldDetect(_ template: String) -> Detection {
        if Self.knownTemplateNames.contains(template) { return .named(template) }
        if template.hasPrefix("mistral") { return .literal("mistral") }
        for literal in Self.fingerprintLiterals where template.contains(literal) {
            return .literal(literal)
        }
        return .unknown
    }
}
