/// One thing the client said, as extracted from a transcript window: a short
/// `heading`, a few plain sentences of `content`, and `evidence`, a verbatim quote
/// from the transcript, so a therapist can always check it. `segIdx` is the
/// transcript segment the quote starts in (0 if unknown) and `segEnd` the one it ends
/// in (the same for a quote inside one segment). One sentence can give several facts,
/// each with its own `evidence`; the span, the milliseconds and the speaker are
/// derived from the quote at commit (`FactSpans`).
/// Ported from Facts.kt. The type keeps its Android name `Fact` on purpose (frozen
/// cache keys and review prompts name the fields); the store's `FactEntity` lives
/// app-side.
public struct Fact: Equatable, Sendable {
    public var heading: String
    public var content: String
    public var evidence: String
    public var segIdx: Int
    public var scope: String
    public var category: String
    public var segEnd: Int

    public static let SCOPE_PROFILE = "profile"
    public static let SCOPE_SESSION = "session"

    public init(heading: String, content: String, evidence: String, segIdx: Int = 0, scope: String = Fact.SCOPE_PROFILE, category: String = "", segEnd: Int? = nil) {
        self.heading = heading
        self.content = content
        self.evidence = evidence
        self.segIdx = segIdx
        self.scope = scope
        self.category = category
        self.segEnd = segEnd ?? segIdx
    }

    /// The ordered JSON the extraction cache stores (same fields as `Fact.toJson` on
    /// Android, in the same order).
    public func toJson() -> String {
        var out = "{"
        out += "\"heading\":\(JSONValue.escape(heading)),"
        out += "\"content\":\(JSONValue.escape(content)),"
        out += "\"evidence\":\(JSONValue.escape(evidence)),"
        out += "\"seg\":\(segIdx),"
        out += "\"segEnd\":\(segEnd),"
        out += "\"scope\":\(JSONValue.escape(scope)),"
        out += "\"category\":\(JSONValue.escape(category))"
        return out + "}"
    }

    /// Reads what `toJson` wrote; missing fields fall back as Android's `Fact.fromJson` does.
    public static func fromJson(_ o: JSONValue) -> Fact {
        // Trims only where Android trims (Facts.kt `Fact.fromJson`): heading/content/
        // evidence/scope keep their text (org.json `optString` does not trim), scope
        // falls back to the profile scope when blank (Kotlin `ifBlank`), and
        // `category` is trimmed with Kotlin `trim()` semantics.
        let scopeRaw = o.string("scope")
        let scope = TextUtil.isBlank(scopeRaw) ? SCOPE_PROFILE : scopeRaw
        let seg = intOf(o["seg"]) ?? 0
        let segEnd = intOf(o["segEnd"]) ?? seg
        return Fact(
            heading: o.string("heading"),
            content: o.string("content"),
            evidence: o.string("evidence"),
            segIdx: seg,
            scope: scope,
            category: TextUtil.ktTrim(o.string("category")),
            segEnd: segEnd
        )
    }

    public static func fromJsonString(_ s: String) -> Fact? {
        guard let v = JSONValue.parse(s), case .object = v else { return nil }
        return fromJson(v)
    }

    static func intOf(_ v: JSONValue?) -> Int? {
        guard let v else { return nil }
        if case .number(let d) = v { return javaInt(d) }
        if case .string(let s) = v {
            // Android's `JSON.toInteger(String)` is `(int) Double.parseDouble(string)`:
            // doubles coerce (so "3.7" and "1e30" behave like the number forms), and a
            // string parseDouble rejects (modern libcore tolerates padding whitespace,
            // which ktTrim matches) returns nil for the caller's fallback.
            guard let d = Double(TextUtil.ktTrim(s)) else { return nil }
            return javaInt(d)
        }
        return nil
    }

    /// Java's `(int)` double cast (JLS 5.1.3 — the d2i instruction behind
    /// `Number.intValue()`), which is what Android's org.json `JSON.toInteger`
    /// produces for a Double: NaN → 0, and a saturating clamp at the 32-bit bounds.
    /// A plain Swift `Int(d)` would trap on 1e30; Android reads `{"seg":1e30}` as
    /// `Integer.MAX_VALUE` and carries on.
    static func javaInt(_ d: Double) -> Int {
        if d.isNaN { return 0 }
        if d >= Double(Int32.max) { return Int(Int32.max) }
        if d <= Double(Int32.min) { return Int(Int32.min) }
        return Int(d)
    }
}

/// The review's answer for one candidate: a duplicate of the known fact at index `of`
/// (0-based), or not a duplicate (`of` nil).
public struct Verdict: Equatable, Sendable {
    public let duplicate: Bool
    public let of: Int?

    public init(_ duplicate: Bool, _ of: Int?) {
        self.duplicate = duplicate
        self.of = of
    }

    public func encode() -> String {
        duplicate ? "dup:\(of ?? -1)" : "new"
    }

    public static func decode(_ s: String) -> Verdict? {
        if s == "new" { return Verdict(false, nil) }
        if s.hasPrefix("dup:") {
            let rest = String(s.dropFirst(4))
            guard let of = Int(rest) else { return nil }
            return Verdict(true, of)
        }
        return nil
    }
}

/// Schemas for constrained decoding, verbatim from Facts.kt.
public enum FactSchemas {
    /// Last-resort constrained schema: non-empty strings and a capped list, because a
    /// looser schema let the GPU decoder loop on whitespace.
    public static let EXTRACT_STRICT =
        "{\"type\":\"object\",\"properties\":{\"facts\":{\"type\":\"array\",\"maxItems\":20,\"items\":{\"type\":\"object\",\"properties\":{" +
        "\"heading\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":60}," +
        "\"content\":{\"type\":\"string\",\"minLength\":10,\"maxLength\":300}," +
        "\"evidence\":{\"type\":\"string\",\"minLength\":5,\"maxLength\":160}," +
        "\"scope\":{\"type\":\"string\",\"maxLength\":20}," +
        "\"category\":{\"type\":\"string\",\"maxLength\":60}}," +
        "\"required\":[\"heading\",\"content\",\"evidence\"],\"additionalProperties\":true}}},\"required\":[\"facts\"],\"additionalProperties\":false}"
}
