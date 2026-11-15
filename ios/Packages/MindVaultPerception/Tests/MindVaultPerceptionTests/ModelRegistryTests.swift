import XCTest
@testable import MindVaultPerception

/// Guards the model pins: the URLs, byte sizes and sha256 checksums shared with the Android app
/// (`app/src/main/java/com/therapylocal/app/models/ModelSpec.kt`). If one of these tests fails after
/// a deliberate change, the change was wrong: the ports must download the exact bytes Android
/// benchmarks and ships. The iOS-only sherpa xcframework pin is checked the same way.
final class ModelRegistryTests: XCTestCase {
    func testAllThreeModelsAreRegisteredAndFindable() {
        XCTAssertEqual(
            ["diarization-pyannote3-wespeaker", "embeddinggemma-2-740m", "embeddinggemma-2-740m-onnx"],
            ModelRegistry.all.map(\.id)
        )
        XCTAssertEqual(
            ModelRegistry.diarization,
            ModelRegistry.byId("diarization-pyannote3-wespeaker")
        )
        XCTAssertEqual(ModelRegistry.embed, ModelRegistry.byId("embeddinggemma-2-740m"))
        XCTAssertEqual(ModelRegistry.embedOnnx, ModelRegistry.byId("embeddinggemma-2-740m-onnx"))
        XCTAssertNil(ModelRegistry.byId("nope"))
    }

    func testEveryFileHasASha256SizedNameAndPositiveSize() {
        for model in ModelRegistry.all {
            XCTAssertFalse(model.files.isEmpty, model.id)
            XCTAssertNotNil(model.subdir, model.id)
            for file in model.files {
                XCTAssertEqual(64, file.sha256.count, "\(model.id)/\(file.name)")
                XCTAssertTrue(
                    file.sha256.allSatisfy { $0.isHexDigit },
                    "\(model.id)/\(file.name): \(file.sha256)"
                )
                XCTAssertGreaterThan(file.size, 0, "\(model.id)/\(file.name)")
                XCTAssertTrue(file.url.hasPrefix("https://"), "\(model.id)/\(file.name)")
                XCTAssertEqual(model.totalBytes, model.files.map(\.size).reduce(0, +))
            }
        }
    }

    /// Same names Android downloads under, so a shared model directory could even be reused.
    func testDiarizationFileNames() {
        XCTAssertEqual("model.onnx", ModelRegistry.diarSegmentationFile)
        XCTAssertEqual(
            "wespeaker_en_voxceleb_resnet34_LM.onnx",
            ModelRegistry.diarEmbeddingFile
        )
        XCTAssertEqual(
            [ModelRegistry.diarSegmentationFile, ModelRegistry.diarEmbeddingFile],
            ModelRegistry.diarization.files.map(\.name)
        )
    }

    func testDiarizationPinsMatchAndroid() {
        let files = ModelRegistry.diarization.files
        XCTAssertEqual(2, files.count)
        XCTAssertEqual(
            "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
            files[0].url
        )
        XCTAssertEqual(5_992_913, files[0].size)
        XCTAssertEqual("220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", files[0].sha256)
        XCTAssertEqual(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx",
            files[1].url
        )
        XCTAssertEqual(26_530_550, files[1].size)
        XCTAssertEqual("e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012", files[1].sha256)
    }

    func testEmbedLitertlmPinMatchesAndroid() {
        let files = ModelRegistry.embed.files
        XCTAssertEqual(1, files.count)
        XCTAssertEqual("embeddinggemma-2-740m.litertlm", ModelRegistry.embedFile)
        XCTAssertEqual("embeddinggemma-2-740m.litertlm", files[0].name)
        XCTAssertEqual(
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
            files[0].url
        )
        XCTAssertEqual(484_622_336, files[0].size)
        XCTAssertEqual("e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c", files[0].sha256)
    }

    /// The ONNX text-embedding build, pinned to one revision of
    /// onnx-community/embeddinggemma-2-ONNX; `model_q4.onnx` was re-verified by download
    /// (2026-11-16), the other two are the Hugging Face LFS sha256 oids of that revision.
    func testEmbedOnnxPins() {
        let files = ModelRegistry.embedOnnx.files
        XCTAssertEqual(3, files.count)
        XCTAssertEqual(["model_q4.onnx", "model_q4.onnx_data", "tokenizer.json"], files.map(\.name))
        let rev = "daa72c51243991dfcaf9f9137d2c573d8f7790c0"
        for file in files {
            XCTAssertTrue(
                file.url.hasPrefix("https://huggingface.co/onnx-community/embeddinggemma-2-ONNX/resolve/\(rev)/"),
                file.url
            )
        }
        XCTAssertEqual(490_742, files[0].size)
        XCTAssertEqual("f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9", files[0].sha256)
        XCTAssertEqual(174_028_800, files[1].size)
        XCTAssertEqual("c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49", files[1].sha256)
        XCTAssertEqual(32_170_510, files[2].size)
        XCTAssertEqual("4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4", files[2].sha256)
    }

    /// The xcframework the diarizer runs on, v2.0.0 to match the Android AAR; the SwiftPM
    /// dependency is pinned to the revision whose Package.swift points at exactly this URL and
    /// checksum (verified by download, 2026-11-16).
    func testSherpaOnnxIosXcframeworkPin() {
        XCTAssertEqual(
            "sherpa-onnx-v2.0.0-ios-static.xcframework.zip",
            ModelRegistry.sherpaOnnxIosXcframework.name
        )
        XCTAssertEqual(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v2.0.0-ios-static.xcframework.zip",
            ModelRegistry.sherpaOnnxIosXcframework.url
        )
        XCTAssertEqual(16_162_102, ModelRegistry.sherpaOnnxIosXcframework.size)
        XCTAssertEqual(
            "95550cc9e2d9699eccbc41d6d7462fb83cdfc12dd070d937533f8d0383a38286",
            ModelRegistry.sherpaOnnxIosXcframework.sha256
        )
        // Keep the registry and the manifest honest about which revision is meant.
        XCTAssertEqual("8abb7c8f87eae85b2e75f9a4115236c978980add", ModelRegistry.sherpaOnnxRevision)
    }
}
