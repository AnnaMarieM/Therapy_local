import Foundation

/// One file of one downloadable model, pinned the way `app/build.gradle.kts`
/// pins sherpa-onnx and `models/ModelSpec.kt` pins every model: exact byte size
/// and sha256. A model file is never committed; the URL carries the pin.
public struct ModelFile: Equatable, Sendable {
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

/// A downloadable model: one or more files stored under the app's model
/// directory, in a per-model subdirectory (the Android `ModelSpec` shape, so the
/// iOS port keeps the same layout and the same `.part` resume convention).
public struct ModelSpec: Equatable, Sendable {
    public let id: String
    public let title: String
    public let subdir: String
    public let files: [ModelFile]

    public init(id: String, title: String, subdir: String, files: [ModelFile]) {
        self.id = id
        self.title = title
        self.subdir = subdir
        self.files = files
    }

    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }
}

/// The speech models this package runs, mirroring the whisper entries of the
/// Android `ModelRegistry` (`models/ModelSpec.kt`). Same ids where they exist.
///
/// Sources for the pins:
/// - large-v3-turbo: upstream hosts the q8_0 build of the model the ASR
///   experiments validated on the phone (`research/asr-llm-combo/device/README.md`
///   round 1, Poco/Pixel/Tab CPU + NPU rows; the sha256 below is the LFS oid of
///   the hosted file, independently re-checked 2026-11-16 against
///   `https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main`).
/// - large-v3 q8_0: our own quantisation (round 2), what the Android Quality
///   workflow runs; nobody hosts it, so `url` stays empty exactly like
///   Android's `WHISPER_Q8_URL = ""` and the file is installed by hand.
public enum ModelRegistry {
    private static let hf = "https://huggingface.co"

    /// Whisper large-v3-turbo, ggml q8_0 (874 MB): the plausible phone candidate
    /// per the findings (`research/asr-llm-combo/README.md` — turbo 16.3-17.1 %
    /// Czech / 8.6-9.2 % English WER against 21.9-23.5 % / 15.3-15.6 % for the
    /// shipped Canary, and about half the RAM of large-v3). Upstream weights:
    /// https://huggingface.co/ggerganov/whisper.cpp (Apache-2.0, OpenAI MIT for
    /// the original weights).
    public static let whisperLargeV3TurboQ8 = ModelSpec(
        id: "whisper-large-v3-turbo-q8_0",
        title: "Whisper large-v3-turbo (q8_0)",
        subdir: "whisper-large-v3-turbo-q8_0",
        files: [
            ModelFile(
                name: "ggml-large-v3-turbo-q8_0.bin",
                url: "\(hf)/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
                size: 874_188_075,
                sha256: "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1"
            )
        ]
    )

    /// Whisper large-v3, ggml q8_0 (1.65 GB), the Android Quality pin. Upstream
    /// (ggerganov/whisper.cpp) publishes large-v3 only as f16 and q5_0; this q8_0
    /// was quantised from the f16 file (3,095,033,483 bytes, sha256
    /// 64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2) with
    /// `whisper-quantize`, see `research/asr-llm-combo/device/README.md`
    /// "Round 2". **There is no download address yet** (Android
    /// `WHISPER_Q8_URL = ""`), so the url is empty and the file is put in place
    /// by hand until it is hosted.
    public static let whisperLargeV3Q8 = ModelSpec(
        id: "whisper-large-v3-q8_0",
        title: "Whisper large-v3 (q8_0)",
        subdir: "whisper-large-v3-q8_0",
        files: [
            ModelFile(
                name: "ggml-large-v3-q8_0.bin",
                url: "",
                size: 1_656_538_283,
                sha256: "24bc434f372355688ab9a623077a63e5361a1c41f4d8d648977e39f9b060f09e"
            )
        ]
    )

    public static let all: [ModelSpec] = [whisperLargeV3TurboQ8, whisperLargeV3Q8]

    public static func byId(_ id: String) -> ModelSpec? {
        all.first { $0.id == id }
    }

    /// The default speech model the iOS port downloads: the hosted one.
    public static let `default`: ModelSpec = whisperLargeV3TurboQ8
}
