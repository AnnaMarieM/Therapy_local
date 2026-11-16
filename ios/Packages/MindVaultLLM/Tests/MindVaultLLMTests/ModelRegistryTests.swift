import XCTest
@testable import MindVaultLLM

/// The pinned GGUF entries: exact bytes and sha256 (the HF LFS oid), the ids and the
/// lookups. Nothing here downloads anything.
final class ModelRegistryTests: XCTestCase {
    private func assertHex(_ s: String) {
        XCTAssertEqual(s.count, 64, s)
        XCTAssertTrue(s.allSatisfy { "0123456789abcdef".contains($0) }, s)
    }

    func testEveryEntryIsPinnedByUrlSizeAndSha256() {
        for spec in ModelRegistry.all {
            XCTAssertFalse(spec.subdir.isEmpty, spec.id)
            XCTAssertEqual(spec.files.count, 1, spec.id)
            for f in spec.files {
                XCTAssertTrue(f.url.hasPrefix("https://huggingface.co/ggml-org/gemma-4-"), f.url)
                XCTAssertTrue(f.url.contains("/resolve/main/"), f.url)
                XCTAssertTrue(f.url.hasSuffix(f.name), f.url)
                XCTAssertGreaterThan(f.size, 2_000_000_000, spec.id)
                assertHex(f.sha256)
            }
        }
    }

    /// The pins themselves, byte for byte (a drift here would download a different file
    /// than the one the benchmark validated — fail loudly instead).
    func testThePinnedFilesAreTheOnesTheBenchmarkValidated() {
        XCTAssertEqual(
            ModelRegistry.gemmaE2BQ4_0,
            ModelSpec(id: "gemma-4-e2b-q4_0", title: "Gemma 4 E2B (GGUF Q4_0)", subdir: "gemma-4-e2b-q4_0", files: [
                ModelFile(
                    name: "gemma-4-E2B-it-Q4_0.gguf",
                    url: "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
                    size: 2_841_481_184,
                    sha256: "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52"
                ),
            ])
        )
        XCTAssertEqual(ModelRegistry.gemmaE2BQ8_0.files[0].size, 4_967_491_520)
        XCTAssertEqual(ModelRegistry.gemmaE2BQ8_0.files[0].sha256, "c36d5e6be0d91124d8b995f5ad4e1ab0d307f6d95cdd08308820350dbd96ad15")
        XCTAssertEqual(ModelRegistry.gemmaE4BQ4_0.files[0].size, 4_590_807_392)
        XCTAssertEqual(ModelRegistry.gemmaE4BQ4_0.files[0].sha256, "a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2")
    }

    func testByIdAndDefault() {
        XCTAssertEqual(ModelRegistry.byId("gemma-4-e2b-q4_0"), ModelRegistry.gemmaE2BQ4_0)
        XCTAssertEqual(ModelRegistry.byId("gemma-4-e4b-q4_0"), ModelRegistry.gemmaE4BQ4_0)
        XCTAssertNil(ModelRegistry.byId("gemma-4-e2b"))           // the Android litertlm id is not one of these
        XCTAssertNil(ModelRegistry.byId(""))
        XCTAssertEqual(ModelRegistry.all.count, 3)
        XCTAssertEqual(ModelRegistry.default, ModelRegistry.gemmaE2BQ4_0)
        XCTAssertEqual(ModelRegistry.gemmaE2BQ4_0.totalBytes, 2_841_481_184)
    }
}
