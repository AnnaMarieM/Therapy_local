import Foundation

public enum ClientValidationError: Error, Equatable, Sendable {
    case emptyName
    case nameTooLong
}

public enum VaultError: Error, Equatable, Sendable {
    case locked
    case missingKey
    case invalidPassword
    case encryptionFailed(String)
    case decryptionFailed(String)
    case corruptEnvelope
    case unsupportedVersion(Int)
    case eraseFailed(String)
}

public enum PersistenceError: Error, Equatable, Sendable {
    case notFound
    case corruptData
    case writeFailed(String)
    case readFailed(String)
    case locked
}

public enum CoreError: Error, Equatable, Sendable {
    case invalidRange
    case emptyInput
    case unsupported(String)
}

public enum JobQueueError: Error, Equatable, Sendable {
    case duplicateJob
    case jobNotFound
}

public enum TranscriptionError: Error, Equatable, Sendable {
    case onDeviceUnavailable
    case permissionDenied
    case emptyAudio
    case failed(String)
}

public enum EmbeddingError: Error, Equatable, Sendable {
    case emptyText
    case unavailable
}

public enum AudioCaptureError: Error, Equatable, Sendable {
    case permissionDenied
    case configurationFailed(String)
    case alreadyRunning
    case notRunning
}

public enum VaultStorageError: Error, Equatable, Sendable {
    case unavailable
    case corrupted
}

extension ClientValidationError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .emptyName: return "Enter a first name."
        case .nameTooLong: return "That name is too long."
        }
    }
}

extension VaultError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .locked: return "The vault is locked."
        case .missingKey: return "The vault key is unavailable."
        case .invalidPassword: return "That password does not match."
        case .encryptionFailed(let reason): return "Could not encrypt the vault: \(reason)"
        case .decryptionFailed(let reason): return "Could not decrypt the vault: \(reason)"
        case .corruptEnvelope: return "The vault file is damaged."
        case .unsupportedVersion(let version): return "Unsupported vault version \(version)."
        case .eraseFailed(let reason): return "Could not erase the vault: \(reason)"
        }
    }
}

extension PersistenceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notFound: return "No saved data was found."
        case .corruptData: return "The saved data is damaged."
        case .writeFailed(let reason): return "Could not save: \(reason)"
        case .readFailed(let reason): return "Could not read: \(reason)"
        case .locked: return "The vault is locked."
        }
    }
}

extension TranscriptionError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .onDeviceUnavailable: return "On-device speech recognition is unavailable for this language."
        case .permissionDenied: return "Speech recognition permission is required."
        case .emptyAudio: return "No audio was captured."
        case .failed(let reason): return "Transcription failed: \(reason)"
        }
    }
}

extension AudioCaptureError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .permissionDenied: return "Microphone permission is required."
        case .configurationFailed(let reason): return "Audio could not start: \(reason)"
        case .alreadyRunning: return "Recording is already running."
        case .notRunning: return "Recording is not running."
        }
    }
}

extension VaultStorageError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unavailable: return "Vault storage is unavailable."
        case .corrupted: return "Vault storage is damaged."
        }
    }
}
