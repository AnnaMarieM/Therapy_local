import SwiftUI
import MindVaultCore

struct RootView: View {
    let app: AppEnvironment
    @StateObject private var homeModel: HomeViewModel
    @ObservedObject private var vault: VaultController
    @ObservedObject private var router: AppRouter

    init(app: AppEnvironment) {
        self.app = app
        _homeModel = StateObject(
            wrappedValue: HomeViewModel(repository: app.repository, notices: app.notices, clock: app.clock)
        )
        _vault = ObservedObject(wrappedValue: app.vault)
        _router = ObservedObject(wrappedValue: app.router)
    }

    var body: some View {
        Group {
            if vault.isUnlocked {
                unlockedContent
            } else {
                VaultGateView(vault: vault)
            }
        }
        .overlay(alignment: .top) {
            NoticeHost(center: app.notices)
        }
        .task {
            if case .checking = vault.state {
                vault.refresh()
            }
        }
        .onChange(of: vault.isUnlocked) { _, unlocked in
            if unlocked {
                Task { await homeModel.load() }
            } else {
                router.popToRoot()
            }
        }
        .onChange(of: router.summarySession) { _, session in
            if session == nil {
                Task { await homeModel.load() }
            }
        }
    }

    private var unlockedContent: some View {
        NavigationStack(path: $router.path) {
            HomeView(app: app, model: homeModel)
                .navigationDestination(for: AppRoute.self) { route in
                    switch route {
                    case .profile(let clientID):
                        ProfileView(app: app, clientID: clientID)
                    }
                }
        }
        .tint(Color.ink)
        .sheet(isPresented: $router.showingSettings) {
            SettingsView(app: app)
        }
        .fullScreenCover(item: $router.sessionClient, onDismiss: {
            router.presentPendingSummary()
        }) { client in
            SessionView(client: client, app: app)
        }
        .sheet(item: $router.summarySession) { session in
            SessionSummaryView(
                app: app,
                session: session,
                coordinator: app.sessions,
                onDone: { app.router.closeSummary() }
            )
        }
    }
}

#Preview {
    RootView(app: .preview())
}
