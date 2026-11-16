import Foundation

/// Reads the prompt template files shipped as package resources: `Resources/<name>` for
/// the English ones and `Resources/cs/<name>` for the Czech copies (`Prompts.named`
/// builds the relative path). Files are trimmed like Android's `MemoryFiles.prompt`
/// (`app/src/main/java/com/therapylocal/app/memory/MemoryFiles.kt`), which reads
/// `prompts/<name>` from the assets of the app — the same files, byte for byte, copied
/// from `demo/prompts/`. No cache: the read is small and a prompt picked up on the next
/// call mirrors the Android dev-override behaviour.
///
/// The manifest ships the folder with `.copy("Resources")`, which preserves the `cs/`
/// subdirectory; `.process` would flatten it and the Czech copies would collide with the
/// English ones (SwiftPM: "multiple resources named 'extract.system.txt'").
///
/// Not shipped here: `translate.system.txt` and `localize.system.txt` (AD-17) —
/// translation is app-side (see `Prompts.TRANSLATE_SYSTEM`).
public enum PromptStore {
    public enum StoreError: Error, Equatable {
        /// No resource at `path` (or it is not readable UTF-8 text).
        case notFound(String)
        /// The name has no extension (every prompt file is `<name>.txt`).
        case malformed(String)
    }

    /// The folder inside the target the manifest copies (`Resources`), so the lookup is
    /// `Resources/<path>` with `path` either `name` or `cs/name` (`Prompts.named`).
    static let ROOT = "Resources"

    /// Loads and trims the prompt file at the relative path `path` (for example
    /// `extract.system.txt` or `cs/extract.system.txt`).
    public static func load(_ path: String) throws -> String {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard let fileName = parts.last, !fileName.isEmpty, parts.count >= 1 else {
            throw StoreError.malformed(path)
        }
        // Every prompt file is `<stem>.txt`; SwiftPM copies them under their own names.
        guard fileName.hasSuffix(".txt") else { throw StoreError.malformed(path) }
        let stem = String(fileName.dropLast(".txt".count))
        let subdirectory = ([ROOT] + parts.dropLast()).joined(separator: "/")
        guard let url = Bundle.module.url(forResource: stem, withExtension: "txt", subdirectory: subdirectory),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8)
        else {
            throw StoreError.notFound(path)
        }
        // Kotlin `String.trim()`: characters with code point <= 0x20 at both ends.
        return TextUtil.ktTrim(text)
    }

    /// The prompt file `name` (a `Prompts` constant) in `language`: `Prompts.named` builds
    /// the relative path, Czech from the `cs/` folder, English from the root.
    public static func load(_ name: String, _ language: String) throws -> String {
        try load(Prompts.named(name, language))
    }
}
