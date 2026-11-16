import Foundation

/// One downloadable model file: a URL, the byte size it must have, and the sha256 it must match.
/// The checksums pin exactly what Android benchmarks and ships (`app/src/main/java/com/therapylocal
/// /app/models/ModelSpec.kt`); a file whose size or sha256 differs is refused, never used. Model
/// files are never committed to the repository.
public struct ModelFile: Hashable, Sendable {
    public let name: String
    public let url: String
    public let size: Int64
    public let sha256: String

    public init(name: String, url: String, size: Int64, sha256: String) {
        self.name = name
        self.url = url
        self.size = size
        self.sha256 = sha256
    }
}

/// A downloadable model: one or more files stored under the app's model directory, in [subdir].
/// Ported from `com.therapylocal.app.models.ModelSpec` (the download checks; the Android-only
/// context and `.part` resume helpers live with the app).
public struct PerceptionModel: Hashable, Sendable {
    public let id: String
    public let title: String
    public let subdir: String?
    public let files: [ModelFile]

    public init(id: String, title: String, subdir: String?, files: [ModelFile]) {
        self.id = id
        self.title = title
        self.subdir = subdir
        self.files = files
    }

    public var totalBytes: Int64 { files.map(\.size).reduce(0, +) }
}

/// The models the perception layer runs, with the URLs, byte sizes and sha256 checksums shared with
/// the Android app (`ModelRegistry` in ModelSpec.kt). Where a checksum was re-verified for this
/// port, the date is in the comment.
public enum ModelRegistry {
    private static let hf = "https://huggingface.co"

    // MARK: Speaker diarization (docs/AD/AD-06.md)

    public static let diarSegmentationFile = "model.onnx"
    public static let diarEmbeddingFile = "wespeaker_en_voxceleb_resnet34_LM.onnx"

    /// Speaker diarization: pyannote segmentation-3.0 finds who speaks when, a speaker-embedding
    /// model tells the voices apart. Both run on sherpa-onnx (`OfflineSpeakerDiarization`). The
    /// embedding model is `wespeaker_en_voxceleb_resnet34_LM`, the one pyannote's own 3.1 pipeline
    /// pairs with segmentation-3.0, and VoxCeleb covers speakers of many nationalities and
    /// languages, so it is the least tied to one language of the sherpa-onnx speaker models.
    /// Voice identity, not words, so Czech is not a special case. Sources:
    /// https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0 (converted from
    /// pyannote/segmentation-3.0, MIT) and
    /// https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models (WeSpeaker,
    /// Apache-2.0). Checksums are of the files as downloaded (the embedding one also matches the
    /// release's checksum.txt).
    public static let diarization = PerceptionModel(
        id: "diarization-pyannote3-wespeaker",
        title: "Speaker diarization (pyannote 3.0 + WeSpeaker ResNet34)",
        subdir: "diarization-pyannote3-wespeaker",
        files: [
            ModelFile(
                name: diarSegmentationFile,
                url: "\(hf)/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
                size: 5_992_913,
                sha256: "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"
            ),
            ModelFile(
                name: diarEmbeddingFile,
                url: "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/\(diarEmbeddingFile)",
                size: 26_530_550,
                sha256: "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012"
            ),
        ]
    )

    // MARK: EmbeddingGemma 2 740M

    /// The LiteRT-LM build the Android app runs (text + audio into one 768-d space). Recorded here
    /// for parity: an iOS runtime for this exact file does not exist yet (litertlm), so the iOS
    /// text path is the ONNX build below; see INTEGRATION.md "Text embeddings".
    public static let embedFile = "embeddinggemma-2-740m.litertlm"

    public static let embed = PerceptionModel(
        id: "embeddinggemma-2-740m",
        title: "EmbeddingGemma 2 740M (text + audio)",
        subdir: "embeddinggemma-2-740m",
        files: [
            ModelFile(
                name: embedFile,
                url: "\(hf)/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/\(embedFile)",
                size: 484_622_336,
                sha256: "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
            ),
        ]
    )

    /// The ONNX build of EmbeddingGemma 2 for the iOS text path: `onnx/model_q4.onnx` (graph, Q4)
    /// plus its external weights and the tokenizer that feeds it. Text only (the repo has a
    /// separate audio encoder; not registered until the runtime story exists). Source:
    /// https://huggingface.co/onnx-community/embeddinggemma-2-ONNX (Apache-2.0), pinned to revision
    /// daa72c51243991dfcaf9f9137d2c573d8f7790c0. The sha256 of `onnx/model_q4.onnx` was verified
    /// by download on 2026-11-16; the other two are the Hugging Face LFS sha256 oids of the pinned
    /// revision.
    public static let embedOnnx = PerceptionModel(
        id: "embeddinggemma-2-740m-onnx",
        title: "EmbeddingGemma 2 740M (text, ONNX q4)",
        subdir: "embeddinggemma-2-740m-onnx",
        files: [
            ModelFile(
                name: "model_q4.onnx",
                url: "\(hf)/onnx-community/embeddinggemma-2-ONNX/resolve/daa72c51243991dfcaf9f9137d2c573d8f7790c0/onnx/model_q4.onnx",
                size: 490_742,
                sha256: "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9"
            ),
            ModelFile(
                name: "model_q4.onnx_data",
                url: "\(hf)/onnx-community/embeddinggemma-2-ONNX/resolve/daa72c51243991dfcaf9f9137d2c573d8f7790c0/onnx/model_q4.onnx_data",
                size: 174_028_800,
                sha256: "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49"
            ),
            ModelFile(
                name: "tokenizer.json",
                url: "\(hf)/onnx-community/embeddinggemma-2-ONNX/resolve/daa72c51243991dfcaf9f9137d2c573d8f7790c0/tokenizer.json",
                size: 32_170_510,
                sha256: "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4"
            ),
        ]
    )

    // MARK: The iOS runtime itself (a binary, not a model; pinned like one)

    /// sherpa-onnx prebuilt iOS xcframework the diarizer runs on, v2.0.0 to match the Android AAR
    /// (app/build.gradle.kts: sherpa-onnx-2.0.0.aar, sha256
    /// f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc). The SwiftPM package is
    /// pinned by revision (below); that revision's Package.swift carries exactly this URL and
    /// checksum, and the checksum was verified by downloading the archive on 2026-11-16.
    public static let sherpaOnnxIosXcframework = ModelFile(
        name: "sherpa-onnx-v2.0.0-ios-static.xcframework.zip",
        url: "https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v2.0.0-ios-static.xcframework.zip",
        size: 16_162_102,
        sha256: "95550cc9e2d9699eccbc41d6d7462fb83cdfc12dd070d937533f8d0383a38286"
    )

    /// The sherpa-onnx SwiftPM revision this package pins: its Package.swift points at the v2.0.0
    /// xcframeworks and onnxruntime-libs v1.28.2.
    public static let sherpaOnnxRevision = "8abb7c8f87eae85b2e75f9a4115236c978980add"

    public static let all = [diarization, embed, embedOnnx]

    public static func byId(_ id: String) -> PerceptionModel? {
        all.first { $0.id == id }
    }
}
