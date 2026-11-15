import Foundation

public struct AppSettings: Codable, Hashable, Sendable {
    public var autoExtractFacts: Bool
    public var liveCuesEnabled: Bool
    public var hapticsEnabled: Bool
    public var requireBiometrics: Bool
    public var keepAudioAfterTranscription: Bool
    public var preferredLanguage: String
    public var transcriptionModel: String
    public var factModel: String
    public var embeddingModel: String
    public var maxChunkAttempts: Int

    public init(
        autoExtractFacts: Bool = true,
        liveCuesEnabled: Bool = true,
        hapticsEnabled: Bool = true,
        requireBiometrics: Bool = false,
        keepAudioAfterTranscription: Bool = false,
        preferredLanguage: String = "en-US",
        transcriptionModel: String = "on-device-speech",
        factModel: String = "extractive-v1",
        embeddingModel: String = "system-sentence-embedding",
        maxChunkAttempts: Int = 3
    ) {
        self.autoExtractFacts = autoExtractFacts
        self.liveCuesEnabled = liveCuesEnabled
        self.hapticsEnabled = hapticsEnabled
        self.requireBiometrics = requireBiometrics
        self.keepAudioAfterTranscription = keepAudioAfterTranscription
        self.preferredLanguage = preferredLanguage
        self.transcriptionModel = transcriptionModel
        self.factModel = factModel
        self.embeddingModel = embeddingModel
        self.maxChunkAttempts = maxChunkAttempts
    }

    public static let defaults = AppSettings()
}
