import Foundation
import MindVaultCore

struct CapabilityReport: Sendable {
    let onDeviceSpeech: Bool
    let speechDetail: String
    let semanticEmbeddings: Bool
    let embeddingModel: String
    let biometrics: Bool
    let notes: [String]
}

struct CapabilityService: Sendable {
    let transcriber: any SpeechTranscribing
    let embeddings: any EmbeddingProviding
    let authenticator: any LocalAuthenticating

    func report(language: String = "en-US") -> CapabilityReport {
        let speech = transcriber.availability(language: language)
        var notes = [
            "Audio embeddings are not available in the baseline; live cues use the partial transcript.",
            "Gemma extraction and EmbeddingGemma models are not bundled; extraction is deterministic and extractive."
        ]
        if !embeddings.isSemantic {
            notes.append("System sentence embeddings are unavailable; a deterministic hashing embedding is active.")
        }
        return CapabilityReport(
            onDeviceSpeech: speech.onDevice,
            speechDetail: speech.detail,
            semanticEmbeddings: embeddings.isSemantic,
            embeddingModel: embeddings.modelID,
            biometrics: authenticator.canEvaluateBiometrics(),
            notes: notes
        )
    }
}
