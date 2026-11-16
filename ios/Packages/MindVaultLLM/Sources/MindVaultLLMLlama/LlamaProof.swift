import Foundation
import llama
import MindVaultLLM

/// The two `LlmProof` self-tests an engine must pass before `LlamaRunner.open` keeps it,
/// mirroring the Android runner's `proveOutput`/`proveFacts`
/// (app/src/main/java/com/therapylocal/app/engine/runner/LlmRunner.kt): the checker
/// itself is pure (`LlmProof`, Linux-tested); this file only runs the prompts through the
/// loaded engine. A wrong answer throws `LlamaError.proofFailed` with the check's reason,
/// which strikes the app's backend choice — the reply is never logged (only its length).
enum LlamaProof {
    /// Proof 1: the fixed reformat task (four Czech sentences → JSON array), greedy
    /// sampling (`LlmProof.TOP_K` 1, temperature 0, seed 1), checked strictly.
    static func proveOutput(_ m: OpaquePointer, _ c: OpaquePointer, _ v: OpaquePointer) throws {
        let reply = try run(
            m, c, v,
            system: LlmProof.SYSTEM, user: LlmProof.USER,
            maxOutputTokens: LlmProof.MAX_OUTPUT_TOKENS,
            topK: LlmProof.TOP_K, topP: 1.0, temperature: LlmProof.TEMPERATURE, seed: 1
        )
        let verdict = LlmProof.check(reply)
        guard case .ok = verdict else { throw LlamaError.proofFailed(verdict.reason ?? "unknown") }
    }

    /// Proof 2: the app's own extraction instruction on three made-up sentences — the
    /// shape that separated the working backends from the one that merged everything
    /// into a single object (issue #48).
    static func proveFacts(_ m: OpaquePointer, _ c: OpaquePointer, _ v: OpaquePointer) throws {
        let reply = try run(
            m, c, v,
            system: LlmProof.SYSTEM2, user: LlmProof.USER2,
            maxOutputTokens: LlmProof.MAX_OUTPUT_TOKENS2,
            topK: LlmProof.TOP_K, topP: 1.0, temperature: LlmProof.TEMPERATURE, seed: 1
        )
        let verdict = LlmProof.checkFacts(reply)
        guard case .ok = verdict else { throw LlamaError.proofFailed(verdict.reason ?? "unknown") }
    }

    /// One proof call through the runner's generation core. Internal (same module).
    private static func run(
        _ m: OpaquePointer, _ c: OpaquePointer, _ v: OpaquePointer,
        system: String, user: String,
        maxOutputTokens: Int, topK: Int, topP: Double, temperature: Double, seed: UInt32
    ) throws -> String {
        // Fresh conversation for each proof, like the Android `createConversation`.
        llama_memory_clear(llama_get_memory(c), true)
        let prompt = try LlamaRunner.templated(system: system, user: user, template: chatTemplate(m))
        let tokens = try LlamaRunner.tokenize(prompt, vocab: v, addSpecial: true, parseSpecial: true)
        let options = LlamaRunner.AskOptions(
            maxOutputTokens: maxOutputTokens,
            temperature: temperature,
            topK: topK,
            topP: topP,
            seed: seed
        )
        // A proof cannot be stopped by a stale flag from a previous call.
        let (reply, _) = try LlamaRunner.generate(
            model: m, ctx: c, vocab: v, promptTokens: tokens, options: options, onToken: nil, stopFlag: StopFlag()
        )
        return reply
    }

    /// The template the runner chose at load (read again from the metadata; both proofs
    /// run inside `open`, before any ask).
    private static func chatTemplate(_ m: OpaquePointer) -> String {
        LlamaRunner.builtinTemplate(m) ?? "gemma"
    }
}
