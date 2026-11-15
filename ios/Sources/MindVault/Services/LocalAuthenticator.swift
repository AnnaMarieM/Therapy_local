import Foundation
import LocalAuthentication
import MindVaultCore

protocol LocalAuthenticating: Sendable {
    func canEvaluateBiometrics() -> Bool
    func authenticate(reason: String) async throws
}

struct LocalAuthenticator: LocalAuthenticating {
    func canEvaluateBiometrics() -> Bool {
        let context = LAContext()
        var error: NSError?
        return context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    func authenticate(reason: String) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let context = LAContext()
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { success, error in
                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: error ?? VaultError.locked)
                }
            }
        }
    }
}
