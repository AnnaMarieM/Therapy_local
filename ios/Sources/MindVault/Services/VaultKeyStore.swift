import Foundation
import MindVaultCore
import Security

protocol VaultKeyStore: Sendable {
    func load() throws -> Data?
    func save(_ data: Data) throws
    func delete() throws
    func exists() throws -> Bool
}

struct KeychainVaultKeyStore: VaultKeyStore {
    let service: String
    let account: String

    func load() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw VaultError.missingKey
        }
        return data
    }

    func save(_ data: Data) throws {
        try delete()
        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw VaultError.encryptionFailed("keychain \(status)")
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw VaultError.eraseFailed("keychain \(status)")
        }
    }

    func exists() throws -> Bool {
        try load() != nil
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any
        ]
    }
}

final class InMemoryVaultKeyStore: VaultKeyStore, @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?

    init(data: Data? = nil) {
        self.data = data
    }

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return data
    }

    func save(_ data: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        self.data = data
    }

    func delete() throws {
        lock.lock()
        defer { lock.unlock() }
        data = nil
    }

    func exists() throws -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return data != nil
    }
}
