import Foundation

/// One file of one downloadable model, pinned the way `models/ModelSpec.kt` pins every
/// model and `ios/Packages/MindVaultASR` pins its speech models: exact byte size and
/// sha256. A model file is never committed (ios/KANBAN.md); the URL carries the pin.
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

/// A downloadable model: one or more files stored under the app's model directory, in a
/// per-model subdirectory (the Android `ModelSpec` shape, same as MindVaultASR's).
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

/// The Gemma models this package runs, the GGUF counterpart of the Android
/// `ModelRegistry.GEMMA_E2B`/`GEMMA_E4B` entries (`models/ModelSpec.kt`, LiteRT-LM
/// `.litertlm`): same upstream model, the llama.cpp quantised builds instead. The
/// variant/quant is what `bench/bench.py` validates on CI (`--model e2b --quant Q4_0`,
/// `--llama-build b11960`): e2b/e4b resolve to the `ggml-org` builds
/// (`gemma-4-E{2,4}B-it-{quant}.gguf`), and Q4_0 is the quant the port ships with.
///
/// Size and sha256 are the byte size and LFS oid of the hosted file, re-checked
/// 2026-11-16 against `https://huggingface.co/api/models/ggml-org/gemma-4-E2B-it-GGUF/tree/main`
/// and `.../gemma-4-E4B-it-GGUF/tree/main` (the same numbers the docs lane recorded in
/// `scratchpad/ios/PLAN.md`: 2.84 GB / 4.97 GB / 4.59 GB). The checksum is verified at
/// download, before the file is used (a 2.8-4.6 GB GGUF is loaded into memory as-is).
///
/// Ids carry the quant because a different quant is a different file with different
/// memory needs (Android's ids `gemma-4-e2b`/`gemma-4-e4b` name the .litertlm builds).
/// Weights: Gemma is Google's (gemma terms); these GGUF conversions are published by
/// `ggml-org` under the Gemma licence.
public enum ModelRegistry {
    private static let hf = "https://huggingface.co"

    /// Gemma 4 E2B, GGUF Q4_0 (2.84 GB): the default for the port — the variant and quant
    /// the CPU benchmark ran (`bench/bench.py --model e2b --quant Q4_0`) and the smallest
    /// one that leaves room for the vault and the OS on a 6 GB iPhone (AD parity note in
    /// `scratchpad/ios/PLAN.md`).
    public static let gemmaE2BQ4_0 = ModelSpec(
        id: "gemma-4-e2b-q4_0",
        title: "Gemma 4 E2B (GGUF Q4_0)",
        subdir: "gemma-4-e2b-q4_0",
        files: [
            ModelFile(
                name: "gemma-4-E2B-it-Q4_0.gguf",
                url: "\(hf)/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
                size: 2_841_481_184,
                sha256: "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52"
            )
        ]
    )

    /// Gemma 4 E2B, GGUF Q8_0 (4.97 GB): the higher-quality quant of the same build; the
    /// bench workflow also runs it (`bench-experiments.yml` g2-ggml-Q8_0), the app loads it
    /// only on devices with the memory for it.
    public static let gemmaE2BQ8_0 = ModelSpec(
        id: "gemma-4-e2b-q8_0",
        title: "Gemma 4 E2B (GGUF Q8_0)",
        subdir: "gemma-4-e2b-q8_0",
        files: [
            ModelFile(
                name: "gemma-4-E2B-it-Q8_0.gguf",
                url: "\(hf)/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q8_0.gguf",
                size: 4_967_491_520,
                sha256: "c36d5e6be0d91124d8b995f5ad4e1ab0d307f6d95cdd08308820350dbd96ad15"
            )
        ]
    )

    /// Gemma 4 E4B, GGUF Q4_0 (4.59 GB): the stronger model, the Android Quality-ladder
    /// counterpart (`bench-experiments.yml` g4-ggml-Q4_0).
    public static let gemmaE4BQ4_0 = ModelSpec(
        id: "gemma-4-e4b-q4_0",
        title: "Gemma 4 E4B (GGUF Q4_0)",
        subdir: "gemma-4-e4b-q4_0",
        files: [
            ModelFile(
                name: "gemma-4-E4B-it-Q4_0.gguf",
                url: "\(hf)/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf",
                size: 4_590_807_392,
                sha256: "a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2"
            )
        ]
    )

    public static let all: [ModelSpec] = [gemmaE2BQ4_0, gemmaE2BQ8_0, gemmaE4BQ4_0]

    public static func byId(_ id: String) -> ModelSpec? {
        all.first { $0.id == id }
    }

    /// The model the iOS port downloads: the smallest validated one.
    public static let `default`: ModelSpec = gemmaE2BQ4_0
}
