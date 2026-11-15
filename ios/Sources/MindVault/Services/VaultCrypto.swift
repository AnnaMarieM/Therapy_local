import CryptoKit
import Foundation
import MindVaultCore
import Security

final class VaultKeyBox: @unchecked Sendable {
    private let lock = NSLock()
    private var keyData: Data?

    func set(_ data: Data) throws {
        guard data.count == 32 else { throw VaultError.missingKey }
        lock.lock()
        defer { lock.unlock() }
        keyData = data
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        keyData = nil
    }

    func key() throws -> SymmetricKey {
        lock.lock()
        defer { lock.unlock() }
        guard let keyData else { throw VaultError.locked }
        return SymmetricKey(data: keyData)
    }
}

struct CryptoKitVaultCipher: VaultCipher {
    let keyBox: VaultKeyBox

    func seal(_ plaintext: Data, context: String) throws -> Data {
        let key = try keyBox.key()
        let sealed = try AES.GCM.seal(plaintext, using: key, authenticating: Data(context.utf8))
        guard let combined = sealed.combined else {
            throw VaultError.encryptionFailed("missing combined representation")
        }
        return combined
    }

    func open(_ ciphertext: Data, context: String) throws -> Data {
        let key = try keyBox.key()
        do {
            let box = try AES.GCM.SealedBox(combined: ciphertext)
            return try AES.GCM.open(box, using: key, authenticating: Data(context.utf8))
        } catch {
            throw VaultError.decryptionFailed(error.localizedDescription)
        }
    }
}

struct VaultCredential: Codable, Equatable {
    var version: Int
    var salt: Data
    var iterations: Int
    var verifier: Data

    init(version: Int = 1, salt: Data, iterations: Int, verifier: Data) {
        self.version = version
        self.salt = salt
        self.iterations = iterations
        self.verifier = verifier
    }
}

enum SecureRandom {
    static func bytes(_ count: Int) throws -> Data {
        guard count > 0 else { return Data() }
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer -> Int32 in
            guard let base = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, count, base)
        }
        guard status == errSecSuccess else {
            throw VaultError.encryptionFailed("secure random \(status)")
        }
        return data
    }
}
