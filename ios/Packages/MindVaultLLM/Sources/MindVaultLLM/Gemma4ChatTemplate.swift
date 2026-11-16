/// Swift-side rendering of the Gemma 4 canonical chat template, the counterpart of
/// `llama_chat_apply_template` for the model family whose template llama.cpp does not
/// know. The pinned GGUF carries "Google Gemma 4 Canonical Chat Template" — Jinja with
/// `<|turn>`/`<turn|>` turn markers — and llama.cpp b11960 does not fingerprint it:
/// `llm_chat_detect_template` matches 54 known names and 76 marker literals
/// (`llama-chat.cpp:89`), 0 of which occur in this template, and
/// `llama_chat_apply_template` returns -1 for `LLM_CHAT_TEMPLATE_UNKNOWN`
/// (`llama.cpp:524`), so templating through llama.cpp throws on every call.
///
/// The turn format is not imagined: the pinned GGUF's own `tokenizer.chat_template`
/// (18,569 bytes, sha256 `0a2c8073c878ab1da004bee933a998606537bbb62016310352c7285c3f01c5b5`,
/// read from `gemma-4-E2B-it-Q4_0.gguf` at byte offset 15,764,652) was rendered with
/// Jinja2 3.1 for the app's shapes, and this builder reproduces that output byte for
/// byte; `Gemma4ChatTemplateTests` pins the results against the fixture copy of the
/// template. The byte-exact shape for the app's two-message call is:
///
///     <|turn>system\n{system}<turn|>\n<|turn>user\n{user}<turn|>\n<|turn>model\n
///
/// Semantics taken from the template source (and checked against the render):
/// assistant is renamed to `model`; consecutive assistant messages continue the same
/// model turn (no new opener, no separator, one closing marker); every turn closes
/// with `<turn|>\n`; `add_generation_prompt` appends `<|turn>model\n`; a message's
/// content is `| trim`med (Python `str.strip`), and model content first loses its
/// thinking channels (`strip_thinking`). The template's leading `{{ bos_token }}` is
/// deliberately NOT emitted: BOS is added by tokenizing with `addSpecial: true`
/// (b11960 forces `add_bos` for the Gemma 4 pre-type, `llama-vocab.cpp:2996`), and the
/// literal `<bos>` in the text would be parsed as a special token as well — a doubled
/// BOS.
///
/// The builder only engages when the model's OWN template string carries the Gemma 4
/// markers (`markers(in:)` is the derivation gate) — the constants below are the
/// markers found in the pinned template, re-checked against it at every `open`.
public enum Gemma4ChatTemplate {
    /// The turn markers the renderer needs, exactly as the pinned template emits them.
    public struct Markers: Equatable, Sendable {
        /// Opens a turn: `role` + `"\n"` follows (the template builds
        /// `'<|turn>' + role + '\n'` for every role).
        public var turnOpen = "<|turn>"
        /// Closes a turn; `"\n"` follows (`'<turn|>\n'`).
        public var turnClose = "<turn|>"

        public init(turnOpen: String = "<|turn>", turnClose: String = "<turn|>") {
            self.turnOpen = turnOpen
            self.turnClose = turnClose
        }
    }

    /// One message of the conversation (the `llama_chat_message` shape, roles
    /// `system` / `user` / `assistant`; tool roles are not part of the app's shapes
    /// and are not rendered).
    public struct Message: Equatable, Sendable {
        public var role: String
        public var content: String

        public init(role: String, content: String) {
            self.role = role
            self.content = content
        }
    }

    /// The Gemma 4 markers, when the raw template string (the GGUF's
    /// `tokenizer.chat_template`) is one — nil otherwise, so the builder never runs
    /// against a template that does not define this turn format. Every marker is
    /// looked for in the template itself: the renderer is derived from the model's
    /// template, not assumed for it.
    ///
    /// The pinned template spells the newline as the Jinja escape (`'<|turn>system\n'`
    /// — backslash, n: it contains no real newline after a marker at all), while a
    /// template with the escape already resolved carries the newline itself; both are
    /// accepted. The user turn is not a literal in the template at all — it is
    /// composed as `'<|turn>' + role + '\n'` — so that composition is the third piece
    /// of evidence.
    public static func markers(in rawTemplate: String) -> Markers? {
        func opens(_ prefix: String) -> Bool {
            rawTemplate.contains(prefix + "\\n") || rawTemplate.contains(prefix + "\n")
        }
        guard rawTemplate.contains("<|turn>"),
              rawTemplate.contains("<turn|>"),
              opens("<|turn>system"),
              opens("<|turn>model"),
              rawTemplate.contains("'<|turn>' + role") else { return nil }
        return Markers()
    }

    /// The rendered prompt for the app's two-message shape (`system` may be empty;
    /// like the template, a system message opens its turn even when its content is
    /// empty), generation prompt always added — the shape every `ask` and both proofs
    /// use.
    public static func prompt(system: String, user: String, markers: Markers = Markers()) -> String {
        prompt([Message(role: "system", content: system), Message(role: "user", content: user)], addGenerationPrompt: true, markers: markers)
    }

    /// The rendered prompt for a whole turn list. The role/message semantics are the
    /// template's (see the type comment); tool roles and thinking channels are not
    /// rendered — the app never sends them.
    public static func prompt(_ messages: [Message], addGenerationPrompt: Bool, markers: Markers = Markers()) -> String {
        var out = ""
        var previousRawRole: String? = nil  // the template's prev_non_tool_role (no tools here)
        for (index, message) in messages.enumerated() {
            var role = message.role == "assistant" ? "model" : message.role
            // The template's setup block opens the first turn itself and hard-codes the
            // role: a leading `developer` message is rendered inside a `system` turn
            // (`llama-chat.cpp` equivalent: `if role in ['system','developer']` → the
            // literal `'<|turn>system\n'`). Every later system message goes through the
            // loop with its own role name.
            if index == 0, role == "developer" { role = "system" }
            // A run of assistant messages is one model turn: the opener only comes
            // when the previous raw role is not assistant.
            let continuesSameTurn = role == "model" && previousRawRole == "assistant"
            if !continuesSameTurn {
                out += markers.turnOpen + role + "\n"
            }
            // The template trims message content (Jinja `| trim` = Python `str.strip`);
            // model content first loses its thinking channels (`strip_thinking`).
            let content = role == "model" ? stripThinking(message.content) : message.content
            out += jinjaTrim(content)
            // The turn closes unless the NEXT message is an assistant (the same model
            // turn continues — then there is no close and no new opener).
            let nextIsAssistant = index + 1 < messages.count && messages[index + 1].role == "assistant"
            if !(role == "model" && nextIsAssistant) {
                out += markers.turnClose + "\n"
            }
            previousRawRole = message.role
        }
        if addGenerationPrompt {
            out += markers.turnOpen + "model" + "\n"
        }
        return out
    }

    /// Jinja's `| trim` filter, which is Python's `str.strip()`: Unicode whitespace on
    /// both ends. That includes the NBSP (U+00A0) — unlike Kotlin `trim()`
    /// (`TextUtil.ktTrim`, code points ≤ 0x20), so the renderer trims with the
    /// template's rule, not the parser's. (Python also strips the four C0 separators
    /// U+001C…U+001F, which are not Unicode whitespace; they cannot reach here, since
    /// the app's prompt texts are Kotlin-trimmed upstream.)
    static func jinjaTrim(_ s: String) -> String {
        var scalars = Array(s.unicodeScalars)
        while let first = scalars.first, first.properties.isWhitespace { scalars.removeFirst() }
        while let last = scalars.last, last.properties.isWhitespace { scalars.removeLast() }
        return String(String.UnicodeScalarView(scalars))
    }

    /// The template's `strip_thinking` macro: drop every `<|channel>…` segment that
    /// ends at a `<channel|>` boundary, keep the rest verbatim. (Macro body:
    /// `for part in text.split('<channel|>'): if '<|channel>' in part:
    /// result += part.split('<|channel>')[0] else: result += part`.) The caller trims
    /// the result, as the macro does.
    static func stripThinking(_ text: String) -> String {
        var result = ""
        for part in text.components(separatedBy: "<channel|>") {
            if let opener = part.range(of: "<|channel>") {
                result += part[part.startIndex..<opener.lowerBound]
            } else {
                result += part
            }
        }
        return result
    }
}

/// How `LlamaRunner` renders a prompt, decided once per `open`:
/// - `.llamaApply(template)`: llama.cpp fingerprints the template and applies it —
///   the existing behaviour for every supported template (probe-verified);
/// - `.gemma4Swift`: llama.cpp rejected the template (`-1`), but the model's own
///   template carries the Gemma 4 markers — the Swift builder renders it.
/// A template that is neither known to llama.cpp nor Gemma 4 falls back to the
/// built-in `"gemma"` name (`.llamaApply("gemma")`), which llama.cpp always accepts.
public enum ChatTemplatePlan: Equatable, Sendable {
    case llamaApply(String)
    case gemma4Swift

    /// The decision for one loaded model. `llamaSupports` is the llama.cpp probe
    /// (`llama_chat_apply_template` on a trivial message pair, `n < 0` = unsupported)
    /// — injectable so the decision is testable where llama is not linked.
    public static func plan(rawTemplate: String, llamaSupports: (String) -> Bool) -> ChatTemplatePlan {
        if llamaSupports(rawTemplate) { return .llamaApply(rawTemplate) }
        if Gemma4ChatTemplate.markers(in: rawTemplate) != nil { return .gemma4Swift }
        return .llamaApply("gemma")
    }
}
