/// A minimal JSON value tree with a strict-enough parser, standing in for the
/// `org.json.JSONObject`/`JSONArray` the Android code uses. The Android parser is
/// fairly lenient (last duplicate key wins); this one is strict about syntax but
/// never throws — callers get nil for anything it cannot read, and the salvage
/// paths (`FactParser.records`, `LlmProof.Parser`) cover the odd shapes the model
/// produces. Duplicate keys keep the LAST value, like org.json's `optString`.
public enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    /// Parses a complete JSON document; trailing content makes the result nil.
    public static func parse(_ s: String) -> JSONValue? {
        var p = Parser(Array(s.unicodeScalars))
        do {
            let v = try p.value()
            p.ws()
            if p.atEnd { return v }
            return nil
        } catch {
            return nil
        }
    }

    /// The object's value for `key` (org.json `opt`).
    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    /// org.json `optString(key, "")`: strings as they are, other scalars as their
    /// text form (numbers printed integrally when they are whole), missing/null as "".
    public var asString: String {
        switch self {
        case .string(let s): return s
        case .bool(let b): return b ? "true" : "false"
        case .number(let d):
            if d == d.rounded() && abs(d) < 1e15 { return String(Int(d)) }
            return String(d)
        default: return ""
        }
    }

    /// org.json `optDouble(key, fallback)`: numbers as they are, numeric strings parsed,
    /// everything else (including null and non-numeric strings) the fallback.
    public var asDouble: Double? {
        switch self {
        case .number(let d): return d
        case .string(let s):
            let t = s.trimmingCharacters(in: .whitespaces)
            return Double(t)
        default: return nil
        }
    }

    public var asArray: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var asObject: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    /// org.json `optString(key)` on an object member, trimmed.
    public func string(_ key: String) -> String {
        (self[key]?.asString ?? "").trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Writing

    /// The value as a JSON document (used by `Fact.toJson`; keys of objects are not
    /// ordered here — `Fact` builds its own ordered document).
    public var encoded: String { encode() }

    func encode() -> String {
        switch self {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .number(let d): return Self.numberText(d)
        case .string(let s): return Self.escape(s)
        case .array(let a): return "[" + a.map { $0.encode() }.joined(separator: ",") + "]"
        case .object(let o):
            return "{" + o.keys.sorted().map { key in Self.escape(key) + ":" + (o[key]?.encode() ?? "null") }.joined(separator: ",") + "}"
        }
    }

    static func numberText(_ d: Double) -> String {
        if d == d.rounded() && abs(d) < 1e15 { return String(Int(d)) }
        return String(d)
    }

    static func escape(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\t": out += "\\t"
            case "\r": out += "\\r"
            default:
                if c.value < 0x20 {
                    out += String(format: "\\u%04x", c.value)
                } else {
                    out.unicodeScalars.append(c)
                }
            }
        }
        return out + "\""
    }

    // MARK: - Parser

    struct Parser {
        let s: [Unicode.Scalar]
        var p = 0

        init(_ s: [Unicode.Scalar]) { self.s = s }

        var atEnd: Bool { p >= s.count }

        mutating func ws() {
            while p < s.count, TextUtil.isWhitespaceScalar(s[p]) { p += 1 }
        }

        mutating func value() throws -> JSONValue {
            ws()
            guard p < s.count else { throw BadJSON() }
            switch s[p] {
            case "{": return try object()
            case "[": return try array()
            case "\"": return .string(try string())
            case "t": try keyword("true"); return .bool(true)
            case "f": try keyword("false"); return .bool(false)
            case "n": try keyword("null"); return .null
            default: return .number(try number())
            }
        }

        mutating func keyword(_ k: String) throws {
            for c in k.unicodeScalars {
                guard p < s.count, s[p] == c else { throw BadJSON() }
                p += 1
            }
        }

        mutating func object() throws -> JSONValue {
            p += 1 // {
            var o: [String: JSONValue] = [:]
            ws()
            if p < s.count, s[p] == "}" { p += 1; return .object(o) }
            while true {
                ws()
                guard p < s.count, s[p] == "\"" else { throw BadJSON() }
                let k = try string()
                ws()
                guard p < s.count, s[p] == ":" else { throw BadJSON() }
                p += 1
                let v = try value()
                o[k] = v // last duplicate key wins, like org.json
                ws()
                guard p < s.count else { throw BadJSON() }
                if s[p] == "," { p += 1; continue }
                if s[p] == "}" { p += 1; return .object(o) }
                throw BadJSON()
            }
        }

        mutating func array() throws -> JSONValue {
            p += 1 // [
            var a: [JSONValue] = []
            ws()
            if p < s.count, s[p] == "]" { p += 1; return .array(a) }
            while true {
                a.append(try value())
                ws()
                guard p < s.count else { throw BadJSON() }
                if s[p] == "," { p += 1; continue }
                if s[p] == "]" { p += 1; return .array(a) }
                throw BadJSON()
            }
        }

        mutating func string() throws -> String {
            p += 1 // opening quote
            var out = String.UnicodeScalarView()
            while true {
                guard p < s.count else { throw BadJSON() }
                let c = s[p]
                if c == "\"" { p += 1; return String(out) }
                if c == "\\" {
                    p += 1
                    guard p < s.count else { throw BadJSON() }
                    switch s[p] {
                    case "\"": out.append("\"")
                    case "\\": out.append("\\")
                    case "/": out.append("/")
                    case "n": out.append("\n")
                    case "t": out.append("\t")
                    case "r": out.append("\r")
                    case "b": out.append("\u{08}")
                    case "f": out.append("\u{0C}")
                    case "u":
                        p += 1
                        let first = try hex4()
                        if first >= 0xD800, first <= 0xDBFF {
                            // Surrogate pair; a lone surrogate cannot exist in a Swift string.
                            guard p + 1 < s.count, s[p] == "\\", p + 5 < s.count, s[p + 1] == "u" else { throw BadJSON() }
                            let save = p
                            p += 2
                            let second = try hex4()
                            guard second >= 0xDC00, second <= 0xDFFF else { p = save; throw BadJSON() }
                            let combined = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00)
                            guard let scalar = Unicode.Scalar(combined) else { throw BadJSON() }
                            out.append(scalar)
                        } else if first >= 0xDC00, first <= 0xDFFF {
                            throw BadJSON()
                        } else if let scalar = Unicode.Scalar(first) {
                            out.append(scalar)
                        } else {
                            throw BadJSON()
                        }
                        continue
                    default: throw BadJSON()
                    }
                    p += 1
                } else if c.value < 0x20 {
                    throw BadJSON()
                } else {
                    out.append(c)
                    p += 1
                }
            }
        }

        mutating func hex4() throws -> UInt32 {
            guard p + 4 <= s.count else { throw BadJSON() }
            var v: UInt32 = 0
            for i in p..<(p + 4) {
                guard let d = s[i].hexDigitValue else { throw BadJSON() }
                v = v * 16 + UInt32(d)
            }
            p += 4
            return v
        }

        mutating func number() throws -> Double {
            let start = p
            if p < s.count, s[p] == "-" { p += 1 }
            while p < s.count, s[p].properties.numericType != nil || s[p] == "." || s[p] == "e" || s[p] == "E" || s[p] == "+" || s[p] == "-" {
                p += 1
            }
            guard p > start, let d = Double(String(String.UnicodeScalarView(s[start..<p]))) else { throw BadJSON() }
            return d
        }
    }

    struct BadJSON: Error {}
}

extension Unicode.Scalar {
    var hexDigitValue: Int? {
        switch value {
        case 0x30...0x39: return Int(value - 0x30)
        case 0x41...0x46: return Int(value - 0x41 + 10)
        case 0x61...0x66: return Int(value - 0x61 + 10)
        default: return nil
        }
    }
}
