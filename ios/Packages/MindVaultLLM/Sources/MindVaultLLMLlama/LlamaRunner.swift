import Foundation
import llama
import MindVaultLLM

/// The native half of the memory LLM on iOS: Gemma 4 via llama.cpp (the pinned
/// `llama` binary target, Metal on device), the counterpart of the LiteRT-LM runner on
/// Android (`app/src/main/java/com/therapylocal/app/engine/runner/LlmRunner.kt`). One
/// engine instance at a time; a fresh conversation per ask (the KV cache is cleared, the
/// model stays loaded).
///
/// All work runs serialized on one private queue — a load takes seconds and a call
/// blocks the thread, so the app calls this from its own pipeline, exactly like the
/// Android runner runs on the engine process. `onToken` fires on that queue. Calls are
/// synchronous and throwing; `ask` streams pieces through `onToken` as they are sampled.
///
/// Numbers only cross the API back to the app (`LlamaAskStats`); reply text never goes
/// to any log here.
public final class LlamaRunner: @unchecked Sendable {
    private let queue = DispatchQueue(label: "mindvault.llm.runner", qos: .userInitiated)

    private var model: OpaquePointer?  // llama_model *
    private var ctx: OpaquePointer?    // llama_context *
    private var vocab: OpaquePointer?  // const llama_vocab *

    /// The context in tokens, as the engine actually created it (the app logs it like
    /// Android's `MemoryLlm` logs max_tokens).
    public private(set) var contextTokens = 0

    /// The raw template from the model metadata ("tokenizer.chat_template"), or the
    /// built-in "gemma" name when the GGUF does not carry it. The strategy below
    /// decides who renders it.
    private var chatTemplate = "gemma"

    /// How prompts are rendered for the loaded model: through llama.cpp when it
    /// fingerprints the template, through the Swift Gemma-4 builder when it does not
    /// (the pinned GGUF's template — see `ChatTemplatePlan.plan`).
    private var templateStrategy: ChatTemplatePlan = .llamaApply("gemma")

    /// Set by `stop()` from any thread, checked between decode calls (so a long prefill
    /// is still cut at batch granularity, LLAMA_BATCH of `batchSize` tokens).
    private let stopFlag = StopFlag()

    public init() {}

    // MARK: - Open

    /// What `open` needs, mirroring the fields of Android's `EngineOp.Open`:
    /// `context` is the token budget the call shares (prompt, template and reply —
    /// `PromptBudget.contextFor` computes it app-side), `threads` the generation
    /// threads, `gpuLayers` the Metal offload (99 = all layers, llama.cpp's `-ngl 99`
    /// idiom; 0 = CPU only), `prove` the output self-tests of `LlmProof` (the Android
    /// ladder skips them on the CPU fallback — here the caller decides).
    public struct OpenOptions: Sendable {
        public var context: Int
        public var threads: Int
        public var batchSize: Int
        public var gpuLayers: Int
        public var prove: Bool

        public init(context: Int, threads: Int, batchSize: Int = 512, gpuLayers: Int = 99, prove: Bool = true) {
            self.context = context
            self.threads = threads
            self.batchSize = batchSize
            self.gpuLayers = gpuLayers
            self.prove = prove
        }
    }

    /// Loads the GGUF at `modelPath` and creates the context, then (unless
    /// `options.prove` is false) runs the two `LlmProof` self-tests — a wrong answer
    /// throws and closes everything, so the app's backend ladder strikes the choice.
    /// `onPhase` reports the steps the Android runner names ("load", "context",
    /// "test", "test2") for the app's log spans.
    public func open(modelPath: String, options: OpenOptions, onPhase: ((String) -> Void)? = nil) throws {
        try queue.sync {
            guard model == nil, ctx == nil else { throw LlamaError.alreadyOpen }
            stopFlag.reset()

            onPhase?("load")
            Self.llamaBackendOnce()
            var mp = llama_model_default_params()
            mp.n_gpu_layers = Int32(options.gpuLayers)
            let m = llama_model_load_from_file(modelPath, mp)
            guard let m else {
                throw LlamaError.loadFailed((modelPath as NSString).lastPathComponent)
            }
            model = m
            vocab = llama_model_get_vocab(m)
            chatTemplate = Self.builtinTemplate(m) ?? "gemma"
            templateStrategy = ChatTemplatePlan.plan(rawTemplate: chatTemplate) { Self.probeLlamaTemplate($0) }

            do {
                onPhase?("context")
                var cp = llama_context_default_params()
                cp.n_ctx = UInt32(max(options.context, 1))
                cp.n_batch = UInt32(max(options.batchSize, 1))
                cp.n_ubatch = cp.n_batch
                cp.n_threads = Int32(max(options.threads, 1))
                cp.n_threads_batch = Int32(max(options.threads, 1))
                let c = llama_init_from_model(m, cp)
                guard let c else { throw LlamaError.contextFailed("llama_init_from_model") }
                ctx = c
                contextTokens = Int(llama_n_ctx(c))

                if options.prove {
                    // The proofs go through the SAME prompt rendering ask uses, so a
                    // template problem fails here, inside open, like Android's ladder.
                    let templater: (String, String) throws -> String = { [strategy = templateStrategy] system, user in
                        try Self.templated(system: system, user: user, strategy: strategy)
                    }
                    onPhase?("test")
                    try LlamaProof.proveOutput(m, c, vocab!, templater: templater)
                    onPhase?("test2")
                    try LlamaProof.proveFacts(m, c, vocab!, templater: templater)
                }
            } catch {
                // One that fails on the way is closed here (the Android open does the same);
                // only free what exists — llama_free(NULL) is not a contract.
                if let c = ctx {
                    llama_free(c)
                    ctx = nil
                }
                llama_model_free(m)
                model = nil
                vocab = nil
                contextTokens = 0
                throw error
            }
        }
    }

    // MARK: - Ask

    /// One call's tuning, the fields of Android's `EngineOp.Ask` that the runner sees.
    /// Defaults mirror the app's extraction call (`MemoryConfig.EXTRACT_TEMPERATURE`
    /// 0.1 / `EXTRACT_TOP_K` 20; topP 0.9 and seed 1 fixed in the Android runner).
    public struct AskOptions: Sendable {
        public var maxOutputTokens: Int
        public var temperature: Double
        public var topK: Int
        public var topP: Double
        public var seed: UInt32
        /// GBNF grammar for constrained decoding, or nil. A JSON schema needs a
        /// json-schema→GBNF step first (llama.cpp's `common` library is not part of the
        /// pinned xcframework) — see INTEGRATION.md.
        public var grammar: String?

        public init(maxOutputTokens: Int, temperature: Double = 0.1, topK: Int = 20, topP: Double = 0.9, seed: UInt32 = 1, grammar: String? = nil) {
            self.maxOutputTokens = maxOutputTokens
            self.temperature = temperature
            self.topK = topK
            self.topP = topP
            self.seed = seed
            self.grammar = grammar
        }
    }

    /// Numbers of one call (the `LlmCall` fields the Android runner hands to PerfStats,
    /// wall time instead of the engine's internal counters).
    public struct AskStats: Sendable {
        public var promptTokens: Int
        public var outputTokens: Int
        public var wallMs: Int
    }

    /// One fresh conversation: rules in `system`, material in `user` (the app's prompt
    /// shape on Android). Streams every sampled piece through `onToken` (nil = collect
    /// only) and returns the full reply.
    ///
    /// A context that is too small does not trim a long prompt: `llama_decode` returns
    /// -1 and `ask` throws `decodeFailed(-1)`. Only the reply stops cleanly at the
    /// context edge (LiteRT's `maxNumTokens` parity; the caller's `PromptBudget`
    /// estimate exists to prevent both).
    @discardableResult
    public func ask(system: String, user: String, options: AskOptions, onToken: ((String) -> Void)? = nil) throws -> (String, AskStats) {
        try queue.sync {
            guard let m = model, let c = ctx, let v = vocab else { throw LlamaError.notLoaded }
            stopFlag.reset()
            let t0 = DispatchTime.now()

            // Fresh conversation: the same cache, wiped (the Android code creates a new
            // conversation object instead).
            llama_memory_clear(llama_get_memory(c), true)

            let prompt = try Self.templated(system: system, user: user, strategy: templateStrategy)
            let promptTokens = try Self.tokenize(prompt, vocab: v, addSpecial: true, parseSpecial: true)

            let (text, outputTokens) = try Self.generate(model: m, ctx: c, vocab: v, promptTokens: promptTokens, options: options, onToken: onToken, stopFlag: stopFlag)

            let wallMs = Int(DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000
            return (text, AskStats(promptTokens: promptTokens.count, outputTokens: outputTokens, wallMs: wallMs))
        }
    }

    /// Soft stop of the call in flight (or the next one): generation ends at the next
    /// decode boundary and `ask` returns what it has. Nothing to stop → no effect.
    public func stop() {
        stopFlag.request()
    }

    // MARK: - Close

    /// Unloads the engine; safe to call twice, safe before `open`.
    public func close() {
        queue.sync {
            guard model != nil || ctx != nil else { return }
            llama_free(ctx)
            ctx = nil
            llama_model_free(model)
            model = nil
            vocab = nil
            contextTokens = 0
        }
    }

    deinit {
        // Free WITHOUT queue.sync: the last reference can be released on the runner's
        // own queue (e.g. from an `onToken` closure), where `queue.sync` would deadlock
        // (the review's should #7). Nothing else can be in flight here — every method
        // holds `self` until its queue block returns — so the pointers are exclusively
        // ours and freeing them from the releasing thread is safe. `close()` stays
        // idempotent for the caller side; after it ran, these are already nil.
        llama_free(ctx)
        ctx = nil
        llama_model_free(model)
        model = nil
        vocab = nil
        contextTokens = 0
    }

    // MARK: - Generation core (internal, shared with the proofs)

    /// Prefill the prompt, then sample one token per decode step until EOG, the token
    /// budget or the context edge. Static, so `LlamaProof` runs the same loop against an
    /// engine that is still inside `open`. Returns the reply text and the number of
    /// generated tokens.
    static func generate(
        model m: OpaquePointer, ctx c: OpaquePointer, vocab v: OpaquePointer,
        promptTokens: [llama_token], options: AskOptions, onToken: ((String) -> Void)?, stopFlag: StopFlag
    ) throws -> (String, Int) {
        let chain = try Self.samplerChain(vocab: v, options: options)
        defer { llama_sampler_free(chain) }  // the chain owns (frees) the samplers added to it

        var streamer = TokenStreamer(onToken)
        var nProcessed = 0
        var outputTokens = 0
        let nCtx = Int(llama_n_ctx(c))
        let budget = max(options.maxOutputTokens, 1)

        // Prefill: the whole prompt in one batch (llama_decode splits it by n_ubatch
        // internally), logits on the last token. The buffer call needs a local copy (the
        // parameter is immutable).
        var prompt = promptTokens
        if !prompt.isEmpty {
            let n = prompt.count
            try prompt.withUnsafeMutableBufferPointer { bp in
                let batch = llama_batch_get_one(bp.baseAddress, Int32(n))
                let rc = llama_decode(c, batch)
                guard rc == 0 else { throw LlamaError.decodeFailed(rc) }
            }
            nProcessed = n
        }

        while nProcessed < nCtx, outputTokens < budget {
            if stopFlag.isRequested { break }
            // Sample from the logits of the last decoded token. b11960's
            // `llama_sampler_sample` ACCEPTS the token itself (shorthand for
            // apply + accept, llama.h:1647-1652; llama-sampler.cpp:961) — no explicit
            // `llama_sampler_accept` here. A second accept would make a grammar
            // consume each piece twice and throw from an extern "C" entry point
            // (llama-grammar.cpp:1599) → std::terminate. Do not re-add it when
            // bumping the pin unless that revision's sample() stops accepting.
            let id = llama_sampler_sample(chain, c, -1)
            if llama_vocab_is_eog(v, id) { break }
            outputTokens += 1
            streamer.push(id, vocab: v)

            guard nProcessed < nCtx else { break }
            // Decode the sampled token; the logits of this step are what the next
            // iteration samples.
            var one: [llama_token] = [id]
            try one.withUnsafeMutableBufferPointer { bp in
                let batch = llama_batch_get_one(bp.baseAddress, 1)
                let rc = llama_decode(c, batch)
                guard rc == 0 else { throw LlamaError.decodeFailed(rc) }
            }
            nProcessed += 1
        }
        streamer.flush()
        return (streamer.text, outputTokens)
    }

    /// The chain of Android's `SamplerConfig(topK, topP, temperature, seed)`: grammar
    /// first (it masks logits before anything else), then the LiteRT order top-k, top-p,
    /// temperature, and `dist` as the picker. Throws when llama.cpp cannot build the
    /// chain (never expected — the error makes the caller's `ask` throw, not crash).
    static func samplerChain(vocab v: OpaquePointer, options: AskOptions) throws -> UnsafeMutablePointer<llama_sampler> {
        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())
        guard let chain else { throw LlamaError.contextFailed("llama_sampler_chain_init") }
        if let g = options.grammar {
            // "Returns NULL if parsing of grammar_str fails" (llama.h:1529-1531). A
            // malformed GBNF is caller input; a NULL sampler pushed into the chain
            // would SIGSEGV in llama_sampler_chain_apply (b11960 dereferences the
            // entry's ptr, and its NULL guard checks only the iface), so this is a
            // typed error instead. b11960 has no `llama_sampler_chain_free`; the
            // chain is released with `llama_sampler_free` (it takes ownership of the
            // samplers added to it — llama.h:1455).
            guard let grammar = llama_sampler_init_grammar(v, g, "root") else {
                llama_sampler_free(chain)
                throw LlamaError.grammarInvalid
            }
            llama_sampler_chain_add(chain, grammar)
        }
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(Int32(options.topK)))
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(Float(options.topP), 1))
        llama_sampler_chain_add(chain, llama_sampler_init_temp(Float(options.temperature)))
        llama_sampler_chain_add(chain, llama_sampler_init_dist(options.seed))
        return chain
    }

    // MARK: - Prompt

    /// Renders `[system, user]` for one ask or proof, per the plan chosen at open:
    /// llama.cpp applies a template it fingerprints (the existing behaviour for every
    /// supported template), the Swift builder renders the Gemma-4 turn format when
    /// llama.cpp rejected the model's own template.
    static func templated(system: String, user: String, strategy: ChatTemplatePlan) throws -> String {
        switch strategy {
        case .gemma4Swift:
            return Gemma4ChatTemplate.prompt(system: system, user: user)
        case .llamaApply(let template):
            if let applied = applyLlamaChatTemplate(system: system, user: user, template: template) {
                return applied
            }
            // A template llama.cpp does not recognise (it fingerprints 54 known formats
            // and returns -1 for anything else, llama.cpp:524-529) gets ONE retry with
            // the built-in "gemma" name — then throws, naming the rejected template's
            // first line in the error.
            if let applied = applyLlamaChatTemplate(system: system, user: user, template: "gemma") {
                return applied
            }
            throw LlamaError.templatingFailed(-1, Self.firstLine(template))
        }
    }

    /// b11960's `llama_chat_apply_template` does not interpret Jinja: it fingerprints
    /// the 54 known template names/literal sets (`llama-chat.cpp:89`) and returns -1
    /// for anything else (`llama.cpp:524-529`). The probe applies a trivial system+user
    /// pair to learn whether llama.cpp can drive `template` at all — the model is not
    /// touched, so the plan can be decided right after load. (Empirically verified
    /// against libllama built from the pinned commit: the pinned GGUF's template and a
    /// custom template both probe -1, built-in "gemma"/"chatml" both apply.)
    static func probeLlamaTemplate(_ template: String) -> Bool {
        applyLlamaChatTemplate(system: "probe", user: "probe", template: template, cap: 256) != nil
    }

    /// One `llama_chat_apply_template` call with its size retries; nil when llama.cpp
    /// rejects the template (`n < 0` — no buffer size fixes that).
    private static func applyLlamaChatTemplate(system: String, user: String, template: String, cap: Int? = nil) -> String? {
        // `llama_chat_message` keeps raw pointers that `llama_chat_apply_template` reads
        // after the array is built, so every string — the fixed roles too — needs storage
        // that outlives the whole call (an implicit String conversion is valid for one
        // call only).
        var result: String?
        template.withCString { tmpl -> Void in
            system.withCString { sys -> Void in
                user.withCString { usr -> Void in
                    "system".withCString { roleSys -> Void in
                        "user".withCString { roleUsr -> Void in
                            var chat: [llama_chat_message] = [
                                llama_chat_message(role: roleSys, content: sys),
                                llama_chat_message(role: roleUsr, content: usr),
                            ]
                            // The docs recommend 2x the characters of all messages; the system
                            // template can double it again, so 4x and a floor. The probe uses a
                            // small fixed buffer — b11960 answers the FULL required length even
                            // when the buffer is short (and strncpy-truncates without a NUL),
                            // so the sign of `n` and the size bookkeeping stay correct.
                            let firstCap = cap ?? min(4 * (system.utf8.count + user.utf8.count) + 512, Int(Int32.max))
                            var buf = [CChar](repeating: 0, count: firstCap)
                            let n = llama_chat_apply_template(tmpl, &chat, chat.count, true, &buf, Int32(firstCap))
                            guard n >= 0 else { return }  // template unknown to llama.cpp
                            guard n < Int32.max - 1 else { return }
                            if Int(n) <= firstCap - 1 {
                                result = Self.cString(buf)
                                return
                            }
                            // n may exceed the buffer (the caller should re-apply): a second
                            // pass at the exact size the call asked for.
                            let cap2 = Int(n) + 1
                            var buf2 = [CChar](repeating: 0, count: cap2)
                            let n2 = llama_chat_apply_template(tmpl, &chat, chat.count, true, &buf2, Int32(cap2))
                            guard n2 >= 0, Int(n2) <= cap2 - 1 else { return }
                            result = Self.cString(buf2)
                        }
                    }
                }
            }
        }
        return result
    }

    /// The first line of a rejected template, for the error that names the culprit
    /// (the review's should #4); whitespace-trimmed, capped for the log.
    static func firstLine(_ template: String) -> String {
        let line = template.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? template
        let trimmed = TextUtil.ktTrim(line)
        return trimmed.count > 80 ? String(trimmed.prefix(80)) : trimmed
    }

    /// A NUL-terminated `[CChar]` buffer as a Swift string (`String(cString:)` is
    /// deprecated; the buffer is zero-initialized, so the first NUL marks the end).
    private static func cString(_ buf: [CChar]) -> String {
        String(decoding: buf.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    /// "tokenizer.chat_template" out of the GGUF metadata, nil when absent or empty.
    /// b11960 answers via `snprintf`, which reports the REQUIRED length while writing
    /// only `buf_size - 1` characters (`llama-model.cpp:3710-3720`) — an 8 KiB default
    /// buffer would silently truncate the pinned 18,569-byte template, so the buffer
    /// is grown to the returned length and read again.
    static func builtinTemplate(_ m: OpaquePointer) -> String? {
        var cap = 8192
        for _ in 0..<3 {
            var buf = [CChar](repeating: 0, count: cap)
            let n = llama_model_meta_val_str(m, "tokenizer.chat_template", &buf, buf.count)
            guard n > 0 else { return nil }
            if Int(n) <= cap - 1 {
                let s = Self.cString(buf)
                return s.isEmpty ? nil : s
            }
            cap = Int(n) + 1
        }
        return nil
    }

    // MARK: - Tokenize

    static func tokenize(_ text: String, vocab v: OpaquePointer, addSpecial: Bool, parseSpecial: Bool) throws -> [llama_token] {
        let byteLen = text.utf8.count
        // Tokens never outnumber UTF-8 bytes + a handful of specials; the retry covers a
        // model that needs more (llama_tokenize answers the needed count, negative).
        var cap = max(32, byteLen + 32)
        for _ in 0..<2 {
            var tokens = [llama_token](repeating: 0, count: cap)
            let n = text.withCString { c in
                llama_tokenize(v, c, Int32(byteLen), &tokens, Int32(cap), addSpecial, parseSpecial)
            }
            if n >= 0 { return Array(tokens.prefix(Int(n))) }
            cap = Int(-n)
        }
        throw LlamaError.tokenizationFailed
    }

    // MARK: - Backend init

    private static let backendOnce: Void = { llama_backend_init() }()

    static func llamaBackendOnce() {
        _ = backendOnce
    }

    /// Whether the pinned llama.cpp build can offload layers to a GPU (Metal on
    /// Apple) — the app reads it before picking `gpuLayers` (0 = CPU only) and logs
    /// the capability the way Android logs the accelerator list.
    public static var gpuOffloadSupported: Bool { llama_supports_gpu_offload() }
}

/// Streams sampled tokens as text: a piece can split a multi-byte UTF-8 character, so
/// bytes are held until they decode cleanly (the final flush decodes lossily). A tail
/// that can never complete (one bad byte) flushes lossily right away, so a single
/// broken piece cannot silence every later `onToken` callback.
struct TokenStreamer {
    private let sink: ((String) -> Void)?
    private var pending: [UInt8] = []
    private(set) var text = ""

    init(_ sink: ((String) -> Void)?) {
        self.sink = sink
    }

    mutating func push(_ token: llama_token, vocab v: OpaquePointer) {
        // b11960 answers -needed when the piece does not fit the buffer — retry once
        // with the size it asked for instead of silently dropping the token from the
        // stream (the review's nit; the final `text` was unaffected, `onToken` was not).
        var cap = 256
        var buf = [CChar](repeating: 0, count: cap)
        var n = llama_token_to_piece(v, token, &buf, Int32(cap), 0, false)
        if n < 0 {
            cap = Int(-n)
            buf = [CChar](repeating: 0, count: cap)
            n = llama_token_to_piece(v, token, &buf, Int32(cap), 0, false)
        }
        guard n > 0 else { return }
        push(Array(buf.prefix(Int(n)).map { UInt8(bitPattern: $0) }))
    }

    /// The byte-level half (no llama types): appends a piece, emits when the pending
    /// bytes decode cleanly, and flushes lossily when the tail is provably invalid
    /// instead of holding the stream hostage on one bad byte. Testable without llama.
    mutating func push(_ bytes: [UInt8]) {
        pending.append(contentsOf: bytes)
        if let s = String(bytes: pending, encoding: .utf8) {
            emit(s)
            pending.removeAll()
        } else if !TextUtil.isIncompleteUTF8Tail(pending) {
            emit(String(decoding: pending, as: UTF8.self))
            pending.removeAll()
        }
    }

    mutating func flush() {
        guard !pending.isEmpty else { return }
        emit(String(decoding: pending, as: UTF8.self))
        pending.removeAll()
    }

    private mutating func emit(_ s: String) {
        text += s
        sink?(s)
    }
}

/// A stop flag writable from any thread (the app may stop the call from the UI).
final class StopFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var requested = false

    func request() {
        lock.lock()
        requested = true
        lock.unlock()
    }

    var isRequested: Bool {
        lock.lock()
        defer { lock.unlock() }
        return requested
    }

    func reset() {
        lock.lock()
        requested = false
        lock.unlock()
    }
}

/// Why a call or a load failed. Reasons carry no reply text (an inference error can
/// quote the prompt — the Android rule).
public enum LlamaError: Error {
    case notLoaded
    case alreadyOpen
    case loadFailed(String)
    case contextFailed(String)
    /// `llama_chat_apply_template` rejected the template: llama.cpp's return code, and
    /// the first line of the template it rejected (built-in fallback included) so a
    /// log names the culprit.
    case templatingFailed(Int32, String)
    /// `llama_sampler_init_grammar` returned NULL — `AskOptions.grammar` does not
    /// parse as GBNF (`llama.h:1529-1531`).
    case grammarInvalid
    case tokenizationFailed
    case decodeFailed(Int32)
    /// `LlmProof` rejected the model's answer (the reason string of the check).
    case proofFailed(String)
}
