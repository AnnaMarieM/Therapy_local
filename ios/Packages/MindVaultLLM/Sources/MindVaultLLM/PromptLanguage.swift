/// Which language the extraction and review prompts are in. Czech prompts are used
/// only when the app is in Czech and the client speaks Czech: with the English prompts
/// Gemma wrote the facts of a Czech session in English (measured), and with the Czech
/// ones it writes them in Czech.
/// Ported from app/src/main/java/com/therapylocal/app/facts/PromptLanguage.kt.
public enum PromptLanguage {
    public static let EN = "en"
    public static let CS = "cs"

    /// Folder of the Czech prompt files next to the English ones (see `Prompts.named`).
    public static let CS_DIR = "cs"

    /// `appLanguage` is the UI language tag and `clientLanguage` the language code the
    /// client speaks in sessions: Czech prompts only if both are Czech.
    public static func choose(_ appLanguage: String, _ clientLanguage: String) -> String {
        appLanguage.caseInsensitiveCompare(CS) == .orderedSame && clientLanguage.caseInsensitiveCompare(CS) == .orderedSame ? CS : EN
    }
}
