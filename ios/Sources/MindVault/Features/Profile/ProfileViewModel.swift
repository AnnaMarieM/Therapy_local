import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class ProfileViewModel: ObservableObject {
    let clientID: UUID
    @Published private(set) var client: Client?
    @Published private(set) var sessions: [Session] = []
    @Published private(set) var facts: [Fact] = []
    @Published var query = ""
    @Published private(set) var results: [SearchResult] = []
    @Published var showingDeleteConfirm = false

    private let repository: any VaultRepository
    private let searchService: any SearchServing
    private let notices: NoticeCenter

    init(
        clientID: UUID,
        repository: any VaultRepository,
        searchService: any SearchServing,
        notices: NoticeCenter
    ) {
        self.clientID = clientID
        self.repository = repository
        self.searchService = searchService
        self.notices = notices
        #if DEBUG
        // CI screenshots (issue #133): the profile is built by its view before
        // the harness can drive it, so its states seed here. The search query
        // re-runs with the load (below), since no onChange fires for a value
        // the model already carries.
        if ScreenshotHarness.screen == .profileSearch {
            query = "running"
        }
        if ScreenshotHarness.screen == .profileDelete {
            showingDeleteConfirm = true
        }
        #endif
    }

    func load() async {
        do {
            let document = try await repository.loadDocument()
            client = document.client(id: clientID)
            sessions = document.sessions(for: clientID)
            facts = document.facts(for: clientID)
            // A query carried in (the screenshot harness seeds it before the
            // view appears) gets its results with the load.
            if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                await search()
            }
        } catch {
            notices.error(error.localizedDescription)
        }
    }

    func search() async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            results = []
            return
        }
        do {
            results = try await searchService.search(query: trimmed, clientID: clientID)
        } catch {
            notices.error(error.localizedDescription)
        }
    }

    func deleteClient() async -> Bool {
        do {
            try await repository.deleteClient(id: clientID)
            notices.success("Client and local records deleted.")
            return true
        } catch {
            notices.error(error.localizedDescription)
            return false
        }
    }
}
