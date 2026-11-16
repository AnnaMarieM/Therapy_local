import XCTest
@testable import MindVaultPerception

/// Port of `com.therapylocal.app.embed.EmbeddingInputSizeTest` plus the identity and prefix
/// contract (`EmbeddingConfig`).
final class EmbeddingSpecTests: XCTestCase {
    func testTheDefaultTwentySecondLookbackNeeds516TokensSoTheSizeIs1024() {
        XCTAssertEqual(516, EmbeddingSpec.audioTokens(seconds: 20))
        XCTAssertEqual(1024, EmbeddingSpec.liveInputTokens(lookbackS: 20))
    }

    func testAShortLookbackStillHoldsAVoiceQuery() {
        XCTAssertEqual(
            EmbeddingSpec.liveInputTokens(lookbackS: 20),
            EmbeddingSpec.liveInputTokens(lookbackS: 5)
        )
    }

    func testTheSizeGrowsWithTheLookbackAndTopsOutAtTheFullContext() {
        XCTAssertEqual(2048, EmbeddingSpec.liveInputTokens(lookbackS: 81))
        XCTAssertEqual(4096, EmbeddingSpec.liveInputTokens(lookbackS: 82))
        XCTAssertEqual(EmbeddingSpec.maxTokens, EmbeddingSpec.liveInputTokens(lookbackS: 327))
    }

    func testTheChosenSizeAlwaysHoldsTheLookback() {
        // LiveCueSettings.LOOKBACK_S = 5..MAX_LOOKBACK_S, MAX_LOOKBACK_S = (8192-16)/25 = 327.
        for s in 5...327 {
            XCTAssertGreaterThanOrEqual(
                EmbeddingSpec.maxAudioSeconds(tokens: EmbeddingSpec.liveInputTokens(lookbackS: s)),
                s,
                "lookback \(s)"
            )
        }
    }

    func testSizesRoundUpToABuildSize() {
        XCTAssertEqual(128, EmbeddingSpec.inputSize(for: 1))
        XCTAssertEqual(256, EmbeddingSpec.inputSize(for: 129))
        XCTAssertEqual(8192, EmbeddingSpec.inputSize(for: 9000))
    }

    func testIdentityAndSizeMatchTheAndroidConfig() {
        XCTAssertEqual("embeddinggemma-2-740m", EmbeddingSpec.modelID)
        XCTAssertEqual(768, EmbeddingSpec.dim)
        XCTAssertEqual(8192, EmbeddingSpec.maxTokens)
        XCTAssertEqual(25, EmbeddingSpec.audioTokensPerSecond)
        XCTAssertEqual(16, EmbeddingSpec.audioMarginTokens)
        XCTAssertEqual(2048, EmbeddingSpec.pipelineInputTokens)
    }

    /// An hour of context in audio terms: (8192 - 16) / 25.
    func testMaxAudioSeconds() {
        XCTAssertEqual(327, EmbeddingSpec.maxAudioSeconds(tokens: 8192))
        XCTAssertEqual(0, EmbeddingSpec.maxAudioSeconds(tokens: 16))
        XCTAssertEqual(0, EmbeddingSpec.maxAudioSeconds(tokens: 8))
    }

    func testTaskPrefixes() {
        XCTAssertEqual("task: search query | text: ", EmbeddingSpec.queryPrefix)
        XCTAssertEqual("task: search result | text: ", EmbeddingSpec.docPrefix)
        XCTAssertEqual(EmbeddingSpec.queryPrefix, EmbeddingSpec.prefix(query: true))
        XCTAssertEqual(EmbeddingSpec.docPrefix, EmbeddingSpec.prefix(query: false))
    }

    func testNormalizedKeepsAUnitVectorAndFixesAnUnnormalizedOne() {
        XCTAssertEqual([Float]([0.6, 0.8]), EmbeddingSpec.normalized([0.6, 0.8]))
        XCTAssertEqual([Float]([0.6, 0.8]), EmbeddingSpec.normalized([3, 4]))
        XCTAssertEqual([Float]([1]), EmbeddingSpec.normalized([0.0001]))
    }

    /// A zero vector has no direction; it must come back as-is, not NaN.
    func testNormalizedLeavesTheZeroVectorAlone() {
        XCTAssertEqual([Float]([0, 0, 0]), EmbeddingSpec.normalized([0, 0, 0]))
    }
}
