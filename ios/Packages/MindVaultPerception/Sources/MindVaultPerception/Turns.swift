import Foundation

/// One stretch of one speaker's voice found by diarization, in milliseconds from the start of the
/// audio. [speaker] is a cluster number (0, 1, ...), not a role. Ported from
/// `com.therapylocal.app.asr.DiarTurn`.
public struct DiarTurn: Hashable, Sendable {
    public var startMs: Int64
    public var endMs: Int64
    public var speaker: Int32

    public init(startMs: Int64, endMs: Int64, speaker: Int32) {
        self.startMs = startMs
        self.endMs = endMs
        self.speaker = speaker
    }
}

/// A stretch of one speaker as the turn planner sees it: the raw cluster number has already become
/// a label ("S0", "S1", ...). Ported from `com.therapylocal.app.data.TimedTurn`.
public struct TimedTurn: Hashable, Sendable {
    public var startMs: Int64
    public var endMs: Int64
    public var speaker: String

    public init(startMs: Int64, endMs: Int64, speaker: String) {
        self.startMs = startMs
        self.endMs = endMs
        self.speaker = speaker
    }
}

/// How the turns of a client speaking this language are put through the recogniser (AD-22). Ported
/// from `com.therapylocal.app.jobs.TurnMode`.
public enum TurnMode: String, Sendable {
    /// The recogniser writes the text in the language spoken; the translate job makes the English (AD-17).
    case spoken
    /// Fast: the LLM hears the turn and writes the language spoken and the English in one answer.
    case listen
    /// Quality: Whisper writes English from the audio.
    case englishOnly

    /// Fast and Quality have no chunk path: nothing there could hear the audio, so a recording
    /// that cannot be diarized fails the job instead of falling back (TranscribeFlow.run).
    public var hasChunkFallback: Bool { self == .spoken }
}
