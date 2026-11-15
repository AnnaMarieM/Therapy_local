import Foundation

public enum TranscriptClean {
    private static let echoPattern = #"(?i)(transcribe the speech in this audio verbatim|output only the transcript,? nothing else)\.?"#

    public static func stripPromptEcho(_ text: String) -> String {
        let stripped = text.replacingOccurrences(of: echoPattern, with: " ", options: .regularExpression)
        return stripped
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }
}
