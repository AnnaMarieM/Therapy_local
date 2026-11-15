import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class VaultController: ObservableObject {
    enum State: Equatable {
        case checking
        case needsSetup
        case locked
        case working
        case unlocked
        case failed(String)
    }

    @Published private(set) var state: State
    @Published private(set) var biometricAvailable = false
    @Published private(set) var biometricEnabled = false
    @Published private(set) var errorMessage: String?

    let paths: VaultPaths
    private let keyBox: VaultKeyBox
    private let dekStore: any VaultKeyStore
    private let credentialStore: any VaultKeyStore
    private let authenticator: any LocalAuthenticating
    private let clock: any Clock
    private let logger: any AppLogger

    init(
        paths: VaultPaths,
        keyBox: VaultKeyBox,
        dekStore: any VaultKeyStore,
        credentialStore: any VaultKeyStore,
        authenticator: any LocalAuthenticating,
        clock: any Clock,
        logger: any AppLogger,
        initialState: State? = nil
    ) {
        self.paths = paths
        self.keyBox = keyBox
        self.dekStore = dekStore
        self.credentialStore = credentialStore
        self.authenticator = authenticator
        self.clock = clock
        self.logger = logger
        self.state = initialState ?? .checking
    }

    var isUnlocked: Bool {
        state == .unlocked
    }

    func refresh() {
        biometricAvailable = authenticator.canEvaluateBiometrics()
        guard state == .checking else { return }
        do {
            state = try credentialStore.exists() ? .locked : .needsSetup
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func retry() {
        state = .checking
        errorMessage = nil
        refresh()
    }

    func createVault(password: String) async {
        guard password.count >= 8 else {
            errorMessage = "Use at least 8 characters."
            return
        }
        state = .working
        errorMessage = nil
        do {
            let salt = try SecureRandom.bytes(VaultKDF.saltLength)
            let iterations = VaultKDF.defaultIterations
            let verifier = try await derive(password: password, salt: salt, iterations: iterations)
            let credential = VaultCredential(salt: salt, iterations: iterations, verifier: verifier)
            let credentialData = try MindVaultJSON.encode(credential)
            try credentialStore.save(credentialData)
            let dek = try SecureRandom.bytes(32)
            try dekStore.save(dek)
            try keyBox.set(dek)
            try paths.prepare()
            state = .unlocked
            logger.log(LogEntry(level: .info, category: "vault", message: "vault_created"))
        } catch {
            errorMessage = error.localizedDescription
            state = .needsSetup
        }
    }

    func unlock(password: String) async {
        state = .working
        errorMessage = nil
        do {
            guard let credentialData = try credentialStore.load() else {
                throw VaultError.missingKey
            }
            let credential = try MindVaultJSON.decode(VaultCredential.self, from: credentialData)
            let verifier = try await derive(password: password, salt: credential.salt, iterations: credential.iterations)
            guard PBKDF2.constantTimeEquals(verifier, credential.verifier) else {
                throw VaultError.invalidPassword
            }
            guard let dek = try dekStore.load() else {
                throw VaultError.missingKey
            }
            try keyBox.set(dek)
            try paths.prepare()
            state = .unlocked
            logger.log(LogEntry(level: .info, category: "vault", message: "vault_unlocked"))
        } catch {
            errorMessage = error.localizedDescription
            state = .locked
        }
    }

    func unlockWithBiometrics() async {
        guard biometricAvailable else {
            errorMessage = "Biometrics are unavailable on this device."
            return
        }
        state = .working
        errorMessage = nil
        do {
            try await authenticator.authenticate(reason: "Unlock your encrypted MindVault.")
            guard let dek = try dekStore.load() else {
                throw VaultError.missingKey
            }
            try keyBox.set(dek)
            try paths.prepare()
            state = .unlocked
            logger.log(LogEntry(level: .info, category: "vault", message: "vault_unlocked_biometric"))
        } catch {
            errorMessage = error.localizedDescription
            state = .locked
        }
    }

    func lock() {
        keyBox.clear()
        state = .locked
        logger.log(LogEntry(level: .info, category: "vault", message: "vault_locked"))
    }

    func eraseAll() async {
        state = .working
        errorMessage = nil
        do {
            try credentialStore.delete()
            try dekStore.delete()
            try paths.erase()
            keyBox.clear()
            biometricEnabled = false
            state = .needsSetup
            logger.log(LogEntry(level: .warning, category: "vault", message: "vault_erased"))
        } catch {
            errorMessage = error.localizedDescription
            state = .failed(error.localizedDescription)
        }
    }

    func setBiometricEnabled(_ enabled: Bool) {
        guard !enabled || biometricAvailable else { return }
        biometricEnabled = enabled
    }

    private func derive(password: String, salt: Data, iterations: Int) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            PBKDF2.deriveKey(password: password, salt: salt, iterations: iterations, keyLength: VaultKDF.keyLength)
        }.value
    }

    static func previewUnlocked() -> VaultController {
        let keyBox = VaultKeyBox()
        try? keyBox.set(Data(repeating: 7, count: 32))
        return VaultController(
            paths: .preview(),
            keyBox: keyBox,
            dekStore: InMemoryVaultKeyStore(data: Data(repeating: 7, count: 32)),
            credentialStore: InMemoryVaultKeyStore(data: Data()),
            authenticator: LocalAuthenticator(),
            clock: SystemClock(),
            logger: NoopLogger(),
            initialState: .unlocked
        )
    }
}
