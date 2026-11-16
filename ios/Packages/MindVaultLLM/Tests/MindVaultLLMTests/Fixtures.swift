import Foundation
@testable import MindVaultLLM

/// The pinned model metadata shared by the template tests, read from the fixture copy
/// next to this file (`Fixtures/README.md` records where the bytes come from).
enum TestFixtures {
    /// `gemma-4-E2B-it-Q4_0.gguf`'s own `tokenizer.chat_template`, byte for byte.
    static let gemma4ChatTemplate: String = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures/gemma4-chat-template.txt")
        do {
            return try String(contentsOf: url, encoding: .utf8)
        } catch {
            fatalError("cannot read the Gemma 4 template fixture at \(url.path): \(error)")
        }
    }()

    /// The template's value length in the GGUF metadata, and the sha256 of the value.
    static let gemma4ChatTemplateBytes = 18_569
    static let gemma4ChatTemplateSHA256 = "0a2c8073c878ab1da004bee933a998606537bbb62016310352c7285c3f01c5b5"
}
