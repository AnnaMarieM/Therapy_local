import Foundation

/// Deterministic cleaning of raw ASR output, the CONTRACTS.md cleaning contract:
/// normalize whitespace, remove bracketed non-speech markers, preserve words.
/// (The prompt-echo strip lives in MindVaultCore `TranscriptClean.stripPromptEcho`;
/// the app composes the two. This type covers what whisper itself writes.)
/// Pure logic, Linux-tested.
public enum WhisperClean {
    /// Bracketed non-speech markers whisper writes: `[Music]`, `[Applause]`,
    /// `[BLANK_AUDIO]`, `[inaudible]`, `[ Silence ]`. Square brackets only;
    /// content in parentheses is speech annotation we keep.
    private static let bracketed = #"\[(?:[^\[\]\\]|\\.)*\]"#

    /// Removes bracketed non-speech markers and collapses every whitespace run
    /// (spaces, tabs, newlines) into one space; words are never touched.
    public static func clean(_ text: String) -> String {
        let stripped = text.replacingOccurrences(of: bracketed, with: " ", options: .regularExpression)
        return collapsed(stripped)
    }

    /// Cleaning for many texts at once (one per chunk), keeping the order.
    public static func clean(_ texts: [String]) -> [String] {
        texts.map(clean)
    }

    /// Whitespace runs collapsed to one space, edges trimmed. Words preserved as they came.
    public static func collapsed(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace || $0.isNewline }).joined(separator: " ")
    }
}
