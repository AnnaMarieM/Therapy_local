import Foundation

/// Prompt templates: `{{NAME}}` placeholders, and a short version that changes
/// whenever a prompt's text does (cache keys, fact provenance).
/// Ported from the `Prompts` object of Facts.kt. The files themselves ship as package
/// resources and are read by `PromptStore`.
public enum Prompts {
    public static let EXTRACT_SYSTEM = "extract.system.txt"
    public static let EXTRACT_USER = "extract.user.txt"
    public static let REVIEW_SYSTEM = "review.system.txt"
    public static let REVIEW_USER = "review.user.txt"

    /// Who is the therapist: the transcript itself is the user message, so there is no user template.
    public static let ROLES_SYSTEM = "roles.system.txt"

    /// The two translation prompts of docs/AD/AD-17.md; always the English files (they
    /// are written in English and take the language name as a placeholder).
    /// (Not shipped in this package: translation is app-side.)
    public static let TRANSLATE_SYSTEM = "translate.system.txt"
    public static let LOCALIZE_SYSTEM = "localize.system.txt"

    /// The prompt file `name` for `language`: Czech ones are in the cs/ folder with the
    /// same names and placeholders.
    public static func named(_ name: String, _ language: String) -> String {
        language == PromptLanguage.CS ? "\(PromptLanguage.CS_DIR)/\(name)" : name
    }

    /// Replaces every `{{KEY}}` with `vars[KEY]`, keys in sorted order (the Android
    /// caller passes distinct keys, so the order cannot matter, only determinism).
    public static func fill(_ template: String, _ vars: [String: String]) -> String {
        vars.keys.sorted().reduce(template) { acc, key in
            acc.replacingOccurrences(of: "{{\(key)}}", with: vars[key] ?? "")
        }
    }

    public static func version(_ texts: String...) -> String {
        String(hash(texts).prefix(12))
    }

    public static func version(_ texts: [String]) -> String {
        String(hash(texts).prefix(12))
    }

    /// SHA-256 in hex over `parts`, each length-prefixed so different splits never collide.
    public static func hash(_ parts: [String]) -> String {
        var buffer = Data()
        for p in parts {
            let b = Data(p.utf8)
            buffer.append(Data("\(b.count):".utf8))
            buffer.append(b)
        }
        return SHA256.hex(buffer)
    }

    public static func hash(_ parts: String...) -> String {
        hash(parts)
    }
}
