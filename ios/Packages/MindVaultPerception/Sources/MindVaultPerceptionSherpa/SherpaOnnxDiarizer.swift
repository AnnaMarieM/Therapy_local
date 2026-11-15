#if canImport(SherpaOnnx)
import Foundation
import SherpaOnnx
import MindVaultPerception

/// Speaker diarization on sherpa-onnx `OfflineSpeakerDiarization`, the iOS port of
/// `com.therapylocal.app.engine.runner.DiarizeRunner`: pyannote segmentation-3.0 and a WeSpeaker
/// embedding, run on the CPU. One call takes a whole recording; only turns come back. The models
/// are the same files as on Android ([ModelRegistry.diarization]). The wrapper (the
/// `sherpa-onnx` SwiftPM package, pinned in Package.swift) is the only native import in the
/// package; everything else stays pure and Linux-testable.
///
/// The diarizer is not re-entrant: one call at a time, the way Android serializes with a mutex.
/// Calls that fail leave the object usable only when [DiarizerError] is one of the input errors;
/// the caller re-creates the diarizer after a runtime failure, like the Android pool does.
public final class SherpaOnnxDiarizer: Diarizing, @unchecked Sendable {
    public enum DiarizerError: Error, Equatable {
        /// A model file is missing or has another byte size than [ModelRegistry.diarization] pins:
        /// sherpa-onnx can abort natively on a missing or cut-off model, which no catch sees.
        case incompleteFile(name: String, have: Int64, want: Int64)
        /// The runtime reports a sample rate the audio is not in.
        case sampleRateMismatch(want: Int, got: Int)
        /// Silence gave turns at open: the backend "works" but cannot be trusted.
        case selfTestTurns(Int)
        /// A longer recording cannot be taken; the chunk path handles it before this is called.
        case tooManySamples(Int)
    }

    private let wrapper: SherpaOnnxOfflineSpeakerDiarizationWrapper
    private let segmentationPath: String
    private let embeddingPath: String
    private let provider: String

    /// The paths of the two model files, as downloaded and verified. [provider] is the ONNX
    /// execution provider ("cpu", as on Android; "coreml" once measured — AD-09 keeps new
    /// backends out until they are).
    public init(segmentationPath: String, embeddingPath: String, provider: String = "cpu") throws {
        try Self.checkFile(segmentationPath, expected: ModelRegistry.diarization.files[0])
        try Self.checkFile(embeddingPath, expected: ModelRegistry.diarization.files[1])
        var config = Self.makeConfig(
            segmentationPath: segmentationPath, embeddingPath: embeddingPath, provider: provider,
            numClusters: -1
        )
        let created = SherpaOnnxOfflineSpeakerDiarizationWrapper(config: &config)
        guard created.sampleRate == Pcm.sampleRate else {
            throw DiarizerError.sampleRateMismatch(want: Pcm.sampleRate, got: created.sampleRate)
        }
        // Runs 2 s of silence (DiarizeRunner.selfTest): a provider can construct fine and fail at
        // the first run, and silence must give no turns.
        let turns = created.process(samples: [Float](repeating: 0, count: PerceptionConfig.diarizeSelfTestSeconds * Pcm.sampleRate))
        guard turns.isEmpty else { throw DiarizerError.selfTestTurns(turns.count) }
        self.wrapper = created
        self.segmentationPath = segmentationPath
        self.embeddingPath = embeddingPath
        self.provider = provider
    }

    /// The paths [ModelRegistry.diarization] names, inside a model directory.
    public convenience init(modelDirectory: String, provider: String = "cpu") throws {
        try self.init(
            segmentationPath: modelDirectory + "/" + ModelRegistry.diarSegmentationFile,
            embeddingPath: modelDirectory + "/" + ModelRegistry.diarEmbeddingFile,
            provider: provider
        )
    }

    /// Turns of 16 kHz mono PCM16 as float samples (one whole recording, at most
    /// [PerceptionConfig.diarizeMaxSamples]). [speakerHint] > 0 fixes the number of speakers; 0
    /// lets the clustering threshold decide. Silence gives an empty list.
    public func diarize(samples: [Float], speakerHint: Int) throws -> [DiarTurn] {
        guard samples.count <= PerceptionConfig.diarizeMaxSamples else {
            throw DiarizerError.tooManySamples(samples.count)
        }
        // A fixed count replaces the threshold for this call only; the clustering is the one part
        // of the config that can change on a loaded model (DiarizeRunner.diarize).
        var config = Self.makeConfig(
            segmentationPath: segmentationPath, embeddingPath: embeddingPath, provider: provider,
            numClusters: speakerHint > 0 ? speakerHint : -1
        )
        wrapper.setConfig(config: &config)
        return Self.toTurns(wrapper.process(samples: samples))
    }

    /// The config as Android's DiarizeRunner builds it: CPU threads 4, fast clustering with the
    /// 0.5 threshold, min duration on 0.2 s / off 0.5 s. Note the Swift wrapper's defaults differ
    /// (0.3 on), so every value is passed explicitly.
    private static func makeConfig(
        segmentationPath: String, embeddingPath: String, provider: String, numClusters: Int
    ) -> SherpaOnnxOfflineSpeakerDiarizationConfig {
        sherpaOnnxOfflineSpeakerDiarizationConfig(
            segmentation: sherpaOnnxOfflineSpeakerSegmentationModelConfig(
                pyannote: sherpaOnnxOfflineSpeakerSegmentationPyannoteModelConfig(model: segmentationPath),
                numThreads: PerceptionConfig.diarizeThreads,
                debug: 0,
                provider: provider
            ),
            embedding: sherpaOnnxSpeakerEmbeddingExtractorConfig(
                model: embeddingPath,
                numThreads: PerceptionConfig.diarizeThreads,
                debug: 0,
                provider: provider
            ),
            clustering: sherpaOnnxFastClusteringConfig(
                numClusters: numClusters,
                threshold: PerceptionConfig.clusterThreshold
            ),
            minDurationOn: PerceptionConfig.minDurationOnS,
            minDurationOff: PerceptionConfig.minDurationOffS
        )
    }

    /// Both files are checked to be there and complete first (DiarizeRunner.checkFiles).
    private static func checkFile(_ path: String, expected: ModelFile) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        guard size == expected.size else {
            throw DiarizerError.incompleteFile(name: expected.name, have: size, want: expected.size)
        }
    }

    /// Seconds to milliseconds, rounded half up (java.lang.Math.round), sorted by start (then
    /// end), a turn never shorter than 1 ms (DiarizeRunner.toTurns).
    static func toTurns(_ segments: [SherpaOnnxOfflineSpeakerDiarizationSegmentWrapper]) -> [DiarTurn] {
        segments
            .map { segment -> DiarTurn in
                let start = Int64((Double(segment.start) * 1000.0 + 0.5).rounded(.down))
                let end = max(Int64((Double(segment.end) * 1000.0 + 0.5).rounded(.down)), start + 1)
                return DiarTurn(startMs: start, endMs: end, speaker: Int32(segment.speaker))
            }
            .sorted { $0.startMs == $1.startMs ? $0.endMs < $1.endMs : $0.startMs < $1.startMs }
    }
}
#endif
