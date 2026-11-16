import Foundation

/// Speaker diarization of a whole recording (docs/AD/AD-06.md): who speaks when, so the recogniser
/// can transcribe one speaker turn at a time. Ported from `com.therapylocal.app.asr.Diarizer`.
///
/// On iOS the implementation is `MindVaultPerceptionSherpa`'s SherpaOnnxDiarizer (sherpa-onnx
/// `OfflineSpeakerDiarization`, pyannote segmentation-3.0 + WeSpeaker resnet34, the same models as
/// on Android). Tests and previews use a fake. On Linux the runtime half is not built at all: this
/// protocol is the seam that keeps the pure logic (turn wire, planning, assembly) testable without
/// any ONNX dependency.
public protocol Diarizing: Sendable {
    /// Turns of 16 kHz mono PCM16 as float samples in [-1, 1) (`Pcm.toFloats`), one whole
    /// recording, sorted by start. [speakerHint] > 0 fixes the number of speakers; 0 lets the
    /// clustering threshold decide. Silence gives an empty list. Throws when the runtime fails; the
    /// caller falls back to the chunk path ([TranscriptPath]). At most
    /// [PerceptionConfig.diarizeMaxSamples] samples; more takes the chunk path before this is called.
    func diarize(samples: [Float], speakerHint: Int) async throws -> [DiarTurn]
}

/// 16 kHz PCM16 widened to the float samples a diarizer takes (`Pcm.toFloats`, /32768).
public enum Pcm {
    public static let sampleRate = PerceptionConfig.sampleRate

    public static func toFloats(_ pcm: [Int16]) -> [Float] {
        pcm.map { Float($0) / 32_768.0 }
    }

    public static func seconds(_ samples: Int) -> Double {
        Double(samples) / Double(sampleRate)
    }
}
