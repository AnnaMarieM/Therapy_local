import SwiftUI
import MindVaultCore

struct HomeView: View {
    let app: AppEnvironment
    @ObservedObject var model: HomeViewModel

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                    header
                    PrivacyBanner()
                    clientsSection
                    footer
                }
                .padding(.horizontal, PaperSpacing.xl)
                .padding(.top, PaperSpacing.lg)
                .padding(.bottom, PaperSpacing.xxxl)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await model.load()
        }
        .sheet(isPresented: $model.showingAddClient) {
            AddClientSheet(model: model)
        }
        .accessibilityIdentifier("home.screen")
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: PaperSpacing.xs) {
                LabelText("MindVault")
                Text("Welcome back.")
                    .paperText(PaperTypography.lead)
                    .foregroundStyle(Color.inkSoft)
            }
            Spacer()
            Button {
                app.router.showingSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.tonal.opacity(0.45)))
            }
            .accessibilityLabel("Settings")
        }
    }

    private var clientsSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Clients", detail: "\(model.clients.count)")
            if model.clients.isEmpty {
                EmptyState(
                    title: "No clients yet",
                    message: "Add a first name to begin. Sessions and notes stay encrypted on this iPhone."
                )
            } else {
                ForEach(model.clients) { client in
                    Button {
                        app.router.openProfile(client.id)
                    } label: {
                        ClientRow(
                            client: client,
                            sessionCount: model.sessionCount(for: client),
                            lastSession: model.lastSession(for: client)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            Button {
                model.showingAddClient = true
            } label: {
                Label("Add client", systemImage: "plus")
            }
            .buttonStyle(PaperButtonStyle(kind: .secondary))
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            PaperDivider()
            Text("Sessions are transcribed and remembered on this device. Nothing is uploaded.")
                .paperText(PaperTypography.bodySmall)
                .foregroundStyle(Color.inkFaint)
        }
    }
}

private struct ClientRow: View {
    let client: Client
    let sessionCount: Int
    let lastSession: Session?

    var body: some View {
        PaperCard {
            HStack(spacing: PaperSpacing.md) {
                Text(client.initials)
                    .paperText(PaperTypography.title)
                    .foregroundStyle(Color.ink)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(Color.tonal.opacity(0.65)))
                VStack(alignment: .leading, spacing: 3) {
                    Text(client.displayName)
                        .paperText(PaperTypography.title)
                    HStack(spacing: PaperSpacing.sm) {
                        if let lastSession {
                            LabelText(lastSession.dateLabel)
                        } else {
                            LabelText("New client")
                        }
                        Text("·")
                            .paperText(PaperTypography.label)
                            .foregroundStyle(Color.inkFaint)
                        LabelText(sessionCount == 1 ? "1 session" : "\(sessionCount) sessions")
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.inkFaint)
            }
        }
    }
}

struct AddClientSheet: View {
    @ObservedObject var model: HomeViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PaperScreen {
                ScrollView {
                    VStack(alignment: .leading, spacing: PaperSpacing.lg) {
                        Text("A name is enough.")
                            .paperText(PaperTypography.title)
                        Text("The last name is kept as an initial. Everything is encrypted on this iPhone.")
                            .paperText(PaperTypography.body)
                            .foregroundStyle(Color.inkSoft)
                        PaperField(placeholder: "First name", text: $model.firstName)
                            .textContentType(.givenName)
                        PaperField(placeholder: "Last name (optional)", text: $model.lastName)
                            .textContentType(.familyName)
                        PaperField(placeholder: "Starting note (optional)", text: $model.note, axis: .vertical)
                        Button("Add client") {
                            Task { await model.addClient() }
                        }
                        .buttonStyle(PaperButtonStyle(kind: .primary))
                        .disabled(model.firstName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    .padding(PaperSpacing.xl)
                }
            }
            .navigationTitle("New client")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationBackground(Color.paper)
    }
}
