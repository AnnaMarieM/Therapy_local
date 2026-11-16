import Foundation

/// Why a recording was not diarized and took the chunk path instead. The strings are the ones the
/// Android app logs (`TranscribeFlow.diarize` / `diarization_skipped`), so the two ports read the
/// same in traces.
public enum DiarizationSkipReason: String, Sendable {
    /// The diarization model is not on the device: an existing install without it still processes
    /// sessions, on the chunk path.
    case modelMissing = "model_missing"
    /// The diarizer failed (also after its retries); diarization never fails the job.
    case failed = "failed"
    /// The recording is longer than the diarizer can take ([PerceptionConfig.diarizeMaxSamples]).
    case tooLong = "too_long"
    /// Only one voice was found; one-speaker recordings have no turns worth planning (Spoken mode).
    case oneSpeaker = "one_speaker"
}

/// How a transcribe run ended. Ported from `TranscribeFlow.Outcome`.
public struct TurnPathOutcome: Hashable, Sendable {
    public let diarized: Bool
    /// The [DiarizationSkipReason] when the chunk path was taken, nil when the recording was
    /// diarized or had nothing to do.
    public let skipped: DiarizationSkipReason?

    public init(diarized: Bool, skipped: DiarizationSkipReason? = nil) {
        self.diarized = diarized
        self.skipped = skipped
    }
}

/// The pure decision core of the transcribe job (`com.therapylocal.app.jobs.TranscribeFlow`): the
/// recording is diarized whole, cut at the turn boundaries and transcribed turn by turn; when
/// diarization is not possible the job takes today's chunk path (the recogniser per 20 s chunk, the
/// overlaps stitched by [SegmentBuilder], speaker unknown). Diarization never fails the job —
/// except in the Fast and Quality workflows, which have no chunk path (nothing there could hear
/// the audio).
public enum TranscriptPath {
    /// The gates before the diarizer is even asked: a recording that is too long or whose model is
    /// missing skips without an attempt. Nil when the diarizer should run.
    public static func skipBeforeDiarize(durationMs: Int64, modelReady: Bool) -> DiarizationSkipReason? {
        if PerceptionConfig.isTooLong(durationMs: durationMs) { return .tooLong }
        if !modelReady { return .modelMissing }
        return nil
    }

    /// The gate after planning (Spoken mode only): a recording with a single voice has no turns
    /// worth planning, so it keeps the chunk path.
    public static func skipAfterPlanning(speakers: some Collection<String>, mode: TurnMode) -> DiarizationSkipReason? {
        if mode == .spoken && Set(speakers).count < 2 { return .oneSpeaker }
        return nil
    }

    /// True when the caller may fall back to the chunk path for [reason]: Fast and Quality cannot.
    public static func canFallback(_ reason: DiarizationSkipReason, mode: TurnMode) -> Bool {
        mode.hasChunkFallback
    }

    /// The planned turns of a diarized recording, cut with [QuietCut] like the Android job does.
    public static func plannedTurns(_ turns: [DiarTurn], durationMs: Int64, pcm: [Int16]) -> [TimedTurn] {
        TurnPlanner.plan(
            turns.map { TimedTurn(startMs: $0.startMs, endMs: $0.endMs, speaker: "S\($0.speaker)") },
            durationMs: durationMs
        ) { start, end in QuietCut.at(pcm, startMs: start, endMs: end) }
    }

    /// The pieces of a diarized recording whose turns have text, for [UtteranceBuilder.build].
    /// Android builds these from the turns themselves (`it.text.orEmpty()`), so a missing text
    /// cannot exist there and nothing traps; a caller bug here must not abort the process at
    /// `-O` either — a turn past the end of [texts] pads with `""`, like `orEmpty()`.
    public static func pieces(turns: [TimedTurn], texts: [String], english: [String?] = []) -> [Piece] {
        return turns.indices.map { i in
            Piece(
                startMs: turns[i].startMs,
                endMs: turns[i].endMs,
                text: i < texts.count ? texts[i] : "",
                speaker: turns[i].speaker,
                textEn: english.indices.contains(i) ? english[i] : nil
            )
        }
    }

    /// The pieces of the chunk path: each chunk is one piece with no speaker, its own text.
    public static func chunkPieces(_ chunks: [ChunkRow]) -> [Piece] {
        chunks.map { Piece(startMs: $0.startMs, endMs: $0.endMs, text: $0.text ?? "", speaker: nil) }
    }
}
