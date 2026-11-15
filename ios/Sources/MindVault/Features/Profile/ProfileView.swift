import SwiftUI
import MindVaultCore

struct ProfileView: View {
    let app: AppEnvironment
    @StateObject private var model: ProfileViewModel
    @ObservedObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

    init(app: AppEnvironment, clientID: UUID) {
        self.app = app
        _model = StateObject(
            wrappedValue: ProfileViewModel(
                clientID: clientID,
                repository: app.repository,
                searchService: app.search,
                notices: app.notices
            )
        )
        _router = ObservedObject(wrappedValue: app.router)
    }

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                    topBar
                    if let client = model.client {
                        hero(client)
                        PrivacyBanner(compact: true)
                        searchSection
                        factsSection
                        sessionsSection
                        startButton(client)
                    } else {
                        EmptyState(title: "Client not found", message: "This record may have been deleted.")
                    }
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
        .onChange(of: router.summarySession) { _, session in
            if session == nil {
                Task { await model.load() }
            }
        }
        .onChange(of: model.query) { _, _ in
            Task {
                try? await Task.sleep(for: .milliseconds(220))
                await model.search()
            }
        }
        .overlay {
            if model.showingDeleteConfirm {
                PaperConfirmDialog(
                    title: "Delete \(model.client?.displayName ?? "client")?",
                    message: "This removes the client, sessions, transcripts, facts, and pending audio from this iPhone. It cannot be undone.",
                    confirmTitle: "Delete",
                    onConfirm: {
                        Task {
                            if await model.deleteClient() {
                                dismiss()
                            }
                        }
                    },
                    onCancel: { model.showingDeleteConfirm = false }
                )
            }
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.tonal.opacity(0.45)))
            }
            .accessibilityLabel("Back")
            Spacer()
            Button {
                model.showingDeleteConfirm = true
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 16, weight: .medium))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.tonal.opacity(0.45)))
            }
            .accessibilityLabel("Delete client")
        }
    }

    private func hero(_ client: Client) -> some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            Text(client.displayName)
                .paperText(PaperTypography.statement)
            HStack(spacing: PaperSpacing.sm) {
                if let age = client.age {
                    LabelText("\(age) years")
                }
                if !client.occupation.isEmpty {
                    LabelText(client.occupation)
                }
                LabelText(client.normalizedID)
            }
            if !client.initialNote.isEmpty {
                Text(client.initialNote)
                    .paperText(PaperTypography.lead)
                    .foregroundStyle(Color.inkSoft)
            }
        }
    }

    private var searchSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            PaperField(placeholder: "Search this client", text: $model.query)
            if !model.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if model.results.isEmpty {
                    Text("No matches in transcripts or facts.")
                        .paperText(PaperTypography.bodySmall)
                        .foregroundStyle(Color.inkFaint)
                } else {
                    ForEach(model.results) { result in
                        PaperCard(padding: PaperSpacing.md) {
                            VStack(alignment: .leading, spacing: PaperSpacing.xs) {
                                LabelText(result.factID == nil ? "Transcript" : "Fact")
                                Text(result.snippet)
                                    .paperText(PaperTypography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    private var factsSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Memory", detail: "\(model.facts.count)")
            if model.facts.isEmpty {
                Text("Facts appear after a session is processed. They always quote the transcript.")
                    .paperText(PaperTypography.bodySmall)
                    .foregroundStyle(Color.inkFaint)
            } else {
                ForEach(model.facts.prefix(8)) { fact in
                    PaperCard(padding: PaperSpacing.md) {
                        VStack(alignment: .leading, spacing: PaperSpacing.xs) {
                            LabelText(fact.category.title)
                            Text(fact.text)
                                .paperText(PaperTypography.body)
                            Text("“\(fact.evidence)”")
                                .paperText(PaperTypography.lead)
                                .foregroundStyle(Color.inkSoft)
                        }
                    }
                }
            }
        }
    }

    private var sessionsSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Sessions", detail: "\(model.sessions.count)")
            if model.sessions.isEmpty {
                Text("No sessions yet.")
                    .paperText(PaperTypography.bodySmall)
                    .foregroundStyle(Color.inkFaint)
            } else {
                ForEach(model.sessions) { session in
                    PaperCard(padding: PaperSpacing.md) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(session.dateLabel)
                                    .paperText(PaperTypography.action)
                                LabelText("\(session.minuteSecondDuration) · \(session.chunkLabel)")
                            }
                            Spacer()
                            TagPill(text: session.status.rawValue, tint: session.status == .saved ? .rev : .inkSoft)
                        }
                    }
                }
            }
        }
    }

    private func startButton(_ client: Client) -> some View {
        Button {
            app.router.startSession(client)
        } label: {
            Label("Start session", systemImage: "waveform")
        }
        .buttonStyle(PaperButtonStyle(kind: .record))
    }
}
