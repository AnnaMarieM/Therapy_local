/// The proof a backend must pass before the app keeps it (docs/MEMORY.md, issue #48): a fixed
/// reformat-as-JSON task, checked strictly. A backend that loads but computes garbage (Gemma
/// E2B on the Adreno 650 answered one JSON object with duplicate keys instead of five) used to
/// pass "Reply with OK.". Now the model must reformat a short made-up Czech text as a JSON
/// array, and the reply is checked strictly.
///
/// Ported from app/src/main/java/com/therapylocal/app/engine/runner/LlmProof.kt, which is pure
/// Kotlin precisely so the JVM tests cover the checker: this port is pure Swift for the same
/// reason (Linux-testable, `LlmProofTests`). The engine target (`MindVaultLLMLlama`) runs the
/// prompts through a loaded engine in `LlamaProof` and strikes the backend on `.fail`.
///
/// The check does not depend on exact wording: structure, the keys, no duplicate keys, a key
/// word of every fact and the one allowed category word for it (case and diacritics ignored).
/// The text is made up. It is never patient data, and the reply is never logged (only its
/// length and the failure reason).
public enum LlmProof {
    public static let FACTS = [
        "Jana má psa Rexe.",
        "Pracuje jako učitelka v Brně.",
        "Ráda plave.",
        "Má dceru Elišku.",
    ]

    /// Per fact: the one category word the instruction allows for it. A garbled word (the
    /// Adreno 650 wrote "Rodzina", "Pracování") fails.
    public static let CATEGORIES = ["zvíře", "práce", "koníček", "rodina"]

    /// Per fact: every key word must occur in the text of the object that carries the fact
    /// (normalised).
    public static let KEY_WORDS: [[String]] = [
        ["rex"], ["ucitel", "brn"], ["plav"], ["elisk"],
    ]

    public static let KEYS: Set<String> = ["kategorie", "text"]

    /// Greedy sampling and a short answer: the tablet CPU decodes at about 15 tokens/s.
    public static let MAX_OUTPUT_TOKENS = 160
    public static let TOP_K = 1
    public static let TEMPERATURE = 0.0

    /// Like the app's real calls (issue #48): the rules in a system instruction, the material
    /// in the user message.
    public static let SYSTEM = "You convert short Czech sentences into JSON. Reply with a JSON array only, no other text. "
        + "The array has exactly one object per sentence, in the same order. Each object has exactly two keys: \"kategorie\" and \"text\". "
        + "\"text\" is the sentence, copied unchanged. \"kategorie\" is exactly one of these words: \(CATEGORIES.joined(separator: ", ")) "
        + "(zvíře for a pet, práce for a job, koníček for a hobby, rodina for a family member)."

    public static let USER = FACTS.joined(separator: "\n")

    // MARK: - Second proof (issue #48)

    // The reformat task above passed on the tablet's Adreno 650 with the generic Gemma file
    // although that GPU answered the app's extraction prompt with ONE merged object (invented
    // keys, a repeated key). What separated them was not the length (this prompt is shorter
    // than the first proof) but the instruction: "one JSON object per line with keys heading,
    // content, evidence (an exact quote)". So this proof uses exactly that instruction on
    // three made-up sentences. The check is structural and does not depend on the model's
    // wording or on the key names it picks (the CPU writes Klíč/Obsah/Důkaz): at least three
    // objects, three distinct keys each, no repeated key, no empty value, and the last value
    // (the quote) must occur in the text.

    public static let SYSTEM2 = "You are a careful note-taker for a psychotherapist. List the facts the client stated, one JSON object per line with keys heading, content, evidence (an exact quote). Write heading and content in Czech."

    /// Made-up text. Three sentences, so three objects are expected.
    public static let SENTENCES = [
        "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené.",
        "Usínám až po půlnoci a v pět ráno se budím.",
        "Pila jsem proto víc kávy, tři až čtyři hrnky denně.",
    ]

    public static let USER2 = "Klient: Petra\n\nPetra: " + SENTENCES.joined(separator: " ")

    /// The CPU needs about 230 tokens for three objects.
    public static let MAX_OUTPUT_TOKENS2 = 320

    // MARK: - Verdicts

    public enum Verdict: Equatable {
        case ok
        case fail(String)

        /// The failure reason (the Android `Verdict.Fail.reason`), nil when `.ok`.
        public var reason: String? {
            if case .fail(let reason) = self { return reason }
            return nil
        }
    }

    // MARK: - First proof: array of {"kategorie", "text"} objects

    public static func check(_ reply: String) -> Verdict {
        let body = stripFence(TextUtil.ktTrim(reply))
        if TextUtil.isBlank(body) { return .fail("empty_reply") }
        let objects: [[(key: String, value: String)]]
        do {
            var p = Parser(body)
            objects = try p.parseArray()
        } catch ParserError.duplicateKey {
            return .fail("duplicate_key")
        } catch {
            return .fail("invalid_json")
        }
        // Dictionaries for the key-set and lookup checks (duplicates already rejected, so the
        // conversion cannot collide).
        let dicts: [[String: String]] = objects.map { Dictionary(uniqueKeysWithValues: $0.map { ($0.key, $0.value) }) }
        if dicts.count != FACTS.count { return .fail("wrong_count") }
        if dicts.contains(where: { Set($0.keys) != KEYS }) { return .fail("wrong_keys") }
        if dicts.contains(where: { $0.values.contains(where: TextUtil.isBlank) }) { return .fail("empty_value") }
        for (i, words) in KEY_WORDS.enumerated() {
            // Keys are exactly KEYS here (checked above), so both lookups exist like the
            // Kotlin `getValue` does.
            let withFact = dicts.first { o in
                let t = normalise(o["text"] ?? "")
                return words.allSatisfy { TextUtil.contains(t, $0) }
            }
            guard let o = withFact else { return .fail("missing_fact:\(i + 1)") }
            if normalise(o["kategorie"] ?? "") != normalise(CATEGORIES[i]) { return .fail("wrong_category:\(i + 1)") }
        }
        return .ok
    }

    // MARK: - Second proof: one object per sentence, last value the verbatim quote

    public static func checkFacts(_ reply: String) -> Verdict {
        // Fence lines (```json) are not part of the data; objects follow one another, or sit in one array.
        let body = TextUtil.ktTrim(TextUtil.lines(reply)
            .filter { !TextUtil.ktTrim($0).hasPrefix("```") }
            .joined(separator: "\n"))
        if TextUtil.isBlank(body) { return .fail("empty_reply") }
        let objects: [[(key: String, value: String)]]
        do {
            var p = Parser(body)
            objects = try p.parseObjects()
        } catch ParserError.duplicateKey {
            return .fail("duplicate_key")
        } catch {
            return .fail("invalid_json")
        }
        if objects.count < SENTENCES.count { return .fail("too_few_objects") }
        if objects.contains(where: { $0.count != 3 }) { return .fail("wrong_keys") }
        if objects.contains(where: { $0.contains(where: { TextUtil.isBlank($0.value) }) }) { return .fail("empty_value") }
        let text = normalise(SENTENCES.joined(separator: " "))
        let quotes = objects.map { TextUtil.ktTrim(normalise($0.last!.value)) }
        for (i, q) in quotes.enumerated() {
            if q.isEmpty || !TextUtil.contains(text, q) { return .fail("evidence_not_quote:\(i + 1)") }
        }
        if Set(quotes).count != quotes.count { return .fail("repeated_evidence") }
        return .ok
    }

    /// Kotlin `Normalizer.normalize(s, NFD)` minus `\p{M}+`, lowercased: case and diacritics
    /// gone, so "Brně" and "brne" agree.
    public static func normalise(_ s: String) -> String {
        TextUtil.regexReplace(s.decomposedStringWithCanonicalMapping, "\\p{M}+", "").lowercased()
    }

    /// Kotlin `stripFence`: ` ```json\n...``` ` down to the JSON between the fences.
    static func stripFence(_ s: String) -> String {
        guard s.hasPrefix("```") else { return s }
        // Kotlin substringAfter('\n', ""): everything after the first line break, none when absent.
        let afterOpen: String
        if let i = s.firstIndex(of: "\n") { afterOpen = String(s[s.index(after: i)...]) } else { afterOpen = "" }
        if afterOpen.hasSuffix("```") { return TextUtil.ktTrim(String(afterOpen.dropLast(3))) }
        return afterOpen
    }

    enum ParserError: Error {
        case duplicateKey
        case badJSON
    }

    /// A strict parser for an array of flat objects with string values. The lenient
    /// `JSONValue` keeps the last of two equal keys silently; this one throws, because a
    /// repeated key inside one object is exactly the #48 failure shape.
    struct Parser {
        let s: [Character]
        var p = 0

        init(_ s: String) { self.s = Array(s) }

        /// `[ {...}, ... ]`, or `[]`. Order-preserving, like the Kotlin LinkedHashMap.
        mutating func parseArray() throws -> [[(key: String, value: String)]] {
            ws(); try expect("[")
            var out: [[(key: String, value: String)]] = []
            ws()
            if try peek() == "]" { p += 1; try end(); return out }
            loop: while true {
                out.append(try parseObject())
                ws()
                switch try next() {
                case ",": continue
                case "]": break loop
                default: throw ParserError.badJSON
                }
            }
            try end()
            return out
        }

        /// Objects one after another (optionally comma separated), or one array of objects.
        mutating func parseObjects() throws -> [[(key: String, value: String)]] {
            ws()
            if p < s.count, s[p] == "[" { return try parseArray() }
            var out: [[(key: String, value: String)]] = []
            while true {
                ws()
                if p >= s.count { break }
                out.append(try parseObject())
                ws()
                if p < s.count, s[p] == "," { p += 1 }
            }
            return out
        }

        mutating func parseObject() throws -> [(key: String, value: String)] {
            ws(); try expect("{")
            var pairs: [(key: String, value: String)] = []
            var dup = false
            ws()
            if try peek() == "}" { p += 1; return pairs }
            loop: while true {
                ws()
                let k = try parseString()
                ws(); try expect(":"); ws()
                let v = try parseString()
                if pairs.contains(where: { $0.key == k }) { dup = true }
                pairs.append((k, v))
                ws()
                switch try next() {
                case ",": continue
                case "}": break loop
                default: throw ParserError.badJSON
                }
            }
            // Finish the whole object first: a malformed one is invalid JSON, a well-formed
            // one with a repeated key is the #48 shape.
            if dup { throw ParserError.duplicateKey }
            return pairs
        }

        mutating func parseString() throws -> String {
            try expect("\"")
            var out = ""
            while true {
                guard p < s.count else { throw ParserError.badJSON }
                let c = s[p]
                if c == "\"" { p += 1; return out }
                if c == "\\" {
                    p += 1
                    guard p < s.count else { throw ParserError.badJSON }
                    switch s[p] {
                    case "\"", "\\", "/": out.append(s[p]); p += 1
                    case "n": out.append("\n"); p += 1
                    case "t": out.append("\t"); p += 1
                    case "r": out.append("\r"); p += 1
                    case "b": out.append("\u{08}"); p += 1
                    case "f": out.append("\u{0C}"); p += 1
                    case "u":
                        p += 1
                        let first = try hex4()
                        // The JVM parser accepts any 16-bit value, even a lone surrogate; a
                        // Swift string cannot hold one. A valid pair combines like on the
                        // JVM, an unpaired one is a parse error here (the checker would fail
                        // the quote either way).
                        if (0xD800...0xDBFF).contains(first) {
                            guard p + 1 < s.count, s[p] == "\\", p + 5 < s.count, s[p + 1] == "u" else { throw ParserError.badJSON }
                            let save = p
                            p += 2
                            let second = try hex4()
                            guard (0xDC00...0xDFFF).contains(second) else { p = save; throw ParserError.badJSON }
                            let combined = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00)
                            guard let scalar = Unicode.Scalar(combined) else { throw ParserError.badJSON }
                            out.unicodeScalars.append(scalar)
                        } else if (0xDC00...0xDFFF).contains(first) {
                            throw ParserError.badJSON
                        } else if let scalar = Unicode.Scalar(first) {
                            out.unicodeScalars.append(scalar)
                        } else {
                            throw ParserError.badJSON
                        }
                    default: throw ParserError.badJSON
                    }
                } else if isControl(c) {
                    throw ParserError.badJSON
                } else {
                    out.append(c)
                    p += 1
                }
            }
        }

        mutating func hex4() throws -> UInt32 {
            guard p + 4 <= s.count else { throw ParserError.badJSON }
            var v: UInt32 = 0
            for i in p..<(p + 4) {
                guard let d = s[i].hexDigitValue else { throw ParserError.badJSON }
                v = v * 16 + UInt32(d)
            }
            p += 4
            return v
        }

        mutating func ws() {
            while p < s.count, TextUtil.isWhitespaceCharacter(s[p]) { p += 1 }
        }

        func peek() throws -> Character {
            guard p < s.count else { throw ParserError.badJSON }
            return s[p]
        }

        mutating func next() throws -> Character {
            guard p < s.count else { throw ParserError.badJSON }
            let c = s[p]
            p += 1
            return c
        }

        mutating func expect(_ c: Character) throws {
            if try next() != c { throw ParserError.badJSON }
        }

        /// No trailing garbage after the last value (Kotlin `end`).
        mutating func end() throws {
            ws()
            if p != s.count { throw ParserError.badJSON }
        }

        /// Kotlin `c < ' '`: ASCII control characters, each a single Unicode scalar.
        private func isControl(_ c: Character) -> Bool {
            guard let s = c.unicodeScalars.first, c.unicodeScalars.count == 1 else { return false }
            return s.value < 0x20
        }
    }
}

extension Character {
    /// 0-9, A-F, a-f (the parser's `\u` digits, like Kotlin's `toIntOrNull(16)` on the slice).
    var hexDigitValue: Int? {
        guard unicodeScalars.count == 1, let s = unicodeScalars.first else { return nil }
        switch s.value {
        case 0x30...0x39: return Int(s.value - 0x30)
        case 0x41...0x46: return Int(s.value - 0x41 + 10)
        case 0x61...0x66: return Int(s.value - 0x61 + 10)
        default: return nil
        }
    }
}
