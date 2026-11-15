import Foundation
import MindVaultCore
import SwiftUI

enum AppRoute: Hashable {
    case profile(UUID)
}

@MainActor
final class AppRouter: ObservableObject {
    @Published var path: [AppRoute] = []
    @Published var showingSettings = false
    @Published var sessionClient: Client?
    @Published var summarySession: Session?
    @Published var pendingSummary: Session?

    func openProfile(_ clientID: UUID) {
        path.append(.profile(clientID))
    }

    func startSession(_ client: Client) {
        sessionClient = client
    }

    func finishSession(_ session: Session) {
        pendingSummary = session
        sessionClient = nil
    }

    func presentPendingSummary() {
        guard let pending = pendingSummary else { return }
        summarySession = pending
        pendingSummary = nil
    }

    func closeSummary() {
        summarySession = nil
    }

    func popToRoot() {
        path.removeAll()
    }
}
