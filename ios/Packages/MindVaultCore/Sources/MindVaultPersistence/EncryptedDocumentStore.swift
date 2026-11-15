import Foundation
import MindVaultModels

public protocol VaultCipher: Sendable {
    func seal(_ plaintext: Data, context: String) throws -> Data
    func open(_ ciphertext: Data, context: String) throws -> Data
}

public actor EncryptedDocumentStore<Value: Codable & Sendable> {
    public static var documentContext: String { "mindvault.document.v1" }

    private let fileURL: URL
    private let cipher: any VaultCipher
    private let clock: @Sendable () -> Date

    public init(
        fileURL: URL,
        cipher: any VaultCipher,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.fileURL = fileURL
        self.cipher = cipher
        self.clock = clock
    }

    public var url: URL {
        fileURL
    }

    public func exists() -> Bool {
        FileManager.default.fileExists(atPath: fileURL.path)
    }

    public func load() throws -> Value? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        do {
            let data = try Data(contentsOf: fileURL)
            let envelope = try MindVaultJSON.decode(VaultEnvelope.self, from: data)
            guard envelope.schemaVersion == VaultEnvelope.currentSchemaVersion else {
                throw PersistenceError.corruptData
            }
            guard envelope.algorithm == VaultEnvelope.algorithm else {
                throw PersistenceError.corruptData
            }
            let plaintext = try cipher.open(envelope.payload, context: Self.documentContext)
            return try MindVaultJSON.decode(Value.self, from: plaintext)
        } catch let error as PersistenceError {
            throw error
        } catch {
            throw PersistenceError.readFailed(error.localizedDescription)
        }
    }

    public func loadRequired() throws -> Value {
        guard let value = try load() else { throw PersistenceError.notFound }
        return value
    }

    public func save(_ value: Value) throws {
        do {
            let plaintext = try MindVaultJSON.encode(value)
            let payload = try cipher.seal(plaintext, context: Self.documentContext)
            let envelope = VaultEnvelope(payload: payload, createdAt: clock())
            let data = try MindVaultJSON.encode(envelope)
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: fileURL, options: [.atomic])
        } catch let error as PersistenceError {
            throw error
        } catch {
            throw PersistenceError.writeFailed(error.localizedDescription)
        }
    }

    public func delete() throws {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch {
            throw PersistenceError.writeFailed(error.localizedDescription)
        }
    }
}
