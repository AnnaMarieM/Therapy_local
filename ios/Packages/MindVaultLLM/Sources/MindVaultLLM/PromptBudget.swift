import Foundation

/// How many tokens of context an LLM call needs, counted before the model is loaded:
/// the prompt (instructions and the text in it), the chat template and the reply all
/// share the engine's context. A context that is too small does not trim an over-long
/// prompt (`ask` throws `decodeFailed(-1)`); it only stops the reply at the context
/// edge.
///
/// The tokenizer is not reachable from here, so tokens are estimated from the
/// characters, on the safe side. Measured on the phone with Gemma 4: Czech is about
/// 2.7 characters per token (about 2 tokens per word, not the 1.4 of English), English
/// about 4. Ported from app/src/main/java/com/therapylocal/app/facts/PromptBudget.kt.
public enum PromptBudget {
    /// Used when the text has accented letters: below the measured 2.7 for Czech, so the estimate is high.
    static let CHARS_PER_TOKEN_ACCENTED = 2.4
    static let CHARS_PER_TOKEN_PLAIN = 3.5

    /// Chat template and special tokens around the system and user text.
    static let TEMPLATE_TOKENS = 96

    /// On top of the estimated prompt: the estimate is only an estimate.
    static let SAFETY = 1.1

    /// The largest context the app asks an engine for.
    public static let MAX_CONTEXT = 16384
    static let STEP = 512

    /// True when at least 1% of the letters are non-ASCII, the Kotlin rule
    /// (`other * 100 >= letters`) that flags Czech and every other accented language.
    /// Counted over UTF-16 units like Kotlin's chars, with Kotlin's `Char.isLetter()`
    /// (= general category Lu/Ll/Lt/Lm/Lo, NOT the wider Unicode Alphabetic property —
    /// a Roman numeral Ⅻ (Nl) or an astral ideograph's surrogate halves are not
    /// letters to Kotlin; Unicode Alphabetic would count them).
    static func accented(_ text: String) -> Bool {
        var letters = 0
        var other = 0
        for u in text.utf16 {
            // A surrogate code unit is not a letter in UTF-16 (Kotlin sees two
            // non-letter chars for one astral scalar); nil scalar → skip.
            guard let scalar = Unicode.Scalar(u) else { continue }
            switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
                letters += 1
                if u > 127 { other += 1 }
            default: break
            }
        }
        return letters > 0 && other * 100 >= letters
    }

    /// Kotlin `String.length` is UTF-16 units; the estimate is counted on those.
    public static func tokens(_ text: String) -> Int {
        let per = accented(text) ? CHARS_PER_TOKEN_ACCENTED : CHARS_PER_TOKEN_PLAIN
        return Int((Double(text.utf16.count) / per).rounded(.up))
    }

    /// Tokens for `chars` characters of text with accented letters (Czech), for bounds
    /// where the text is not known yet.
    public static func accentedTokens(_ chars: Int) -> Int {
        Int((Double(chars) / CHARS_PER_TOKEN_ACCENTED).rounded(.up))
    }

    /// Context one call needs: prompt, template, a safety margin, and the longest reply allowed.
    public static func required(_ system: String, _ user: String, _ maxOutputTokens: Int) -> Int {
        let estimate = Double(tokens(system) + tokens(user) + TEMPLATE_TOKENS) * SAFETY
        return Int(estimate.rounded(.up)) + maxOutputTokens
    }

    /// The context to load for `needed` tokens: rounded up, never below `floor` (the
    /// size the app always used) or above `MAX_CONTEXT`.
    public static func contextFor(_ needed: Int, _ floor: Int) -> Int {
        let rounded = (needed + STEP - 1) / STEP * STEP
        return min(max(rounded, floor), MAX_CONTEXT)
    }
}
