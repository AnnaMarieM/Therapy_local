import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var document: VaultDocument = .empty()
    @Published var showingAddClient = false
    @Published var firstName = ""
    @Published var lastName = ""
    @Published var note = ""
    @Published var errorMessage: String?

    private let repository: any VaultRepository
    private let notices: NoticeCenter
    private let clock: any Clock

    init(repository: any VaultRepository, notices: NoticeCenter, clock: any Clock) {
        self.repository = repository
        self.notices = notices
        self.clock = clock
    }

    var clients: [Client] {
        document.clients.sorted { $0.updatedAt > $1.updatedAt }
    }

    func load() async {
        do {
            document = try await repository.loadDocument()
        } catch {
            errorMessage = error.localizedDescription
            notices.error(error.localizedDescription)
        }
    }

    func addClient() async {
        do {
            let client = try Client(
                firstName: firstName,
                lastName: lastName,
                initialNote: note,
                createdAt: clock.now()
            ).validated()
            try await repository.upsertClient(client)
            await load()
            firstName = ""
            lastName = ""
            note = ""
            showingAddClient = false
            notices.success("Client added.")
        } catch {
            notices.error(error.localizedDescription)
        }
    }

    func sessionCount(for client: Client) -> Int {
        document.sessions.filter { $0.clientID == client.id }.count
    }

    func lastSession(for client: Client) -> Session? {
        document.sessions(for: client.id).first
    }
}
