import SwiftUI
import MindVaultCore

struct SessionSummaryView: View {
    let app: AppEnvironment
    let session: Session
    @ObservedObject var coordinator: SessionCoordinator
    let onDone: () -> Void
    @State private var transcript: String
    @State private var isSaving = false

    init(app: AppEnvironment, session: Session, coordinator: SessionCoordinator, onDone: @escaping () -> Void) {
        self.app = app
        self.session = session
        self.coordinator = coordinator
        self.onDone = onDone
        _transcript = State(initialValue: session.transcript)
    }

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                    header
                    stats
                    transcriptEditor
                    actions
                }
                .padding(PaperSpacing.xl)
            }
        }
        .interactiveDismissDisabled(isSaving)
        .presentationBackground(Color.paper)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            LabelText("Session review")
            Text("Ready to keep?")
                .paperText(PaperTypography.statement)
            Text("Edit the transcript if speech recognition missed something. Facts are extracted only from this text and always quote it.")
                .paperText(PaperTypography.body)
                .foregroundStyle(Color.inkSoft)
        }
    }

    private var stats: some View {
        HStack(spacing: PaperSpacing.xl) {
            stat(label: "Length", value: session.minuteSecondDuration)
            stat(label: "Chunks", value: "\(session.chunkCount)")
            stat(label: "Words", value: "\(wordCount)")
            Spacer()
        }
    }

    private func stat(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            LabelText(label)
            Text(value)
                .paperText(PaperTypography.action)
                .monospacedDigit()
        }
    }

    private var wordCount: Int {
        transcript.split { $0.isWhitespace || $0.isNewline }.count
    }

    private var transcriptEditor: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            SectionHeader(title: "Transcript")
            TextEditor(text: $transcript)
                .paperText(PaperTypography.body)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 190)
                .padding(PaperSpacing.sm)
                .background(
                    RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous)
                        .fill(Color.tonal.opacity(0.35))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous)
                        .stroke(Color.hairline, lineWidth: 1)
                )
        }
    }

    private var actions: some View {
        VStack(spacing: PaperSpacing.sm) {
            Button {
                Task { await save() }
            } label: {
                if isSaving {
                    ProgressView()
                        .tint(Color.onColor)
                } else {
                    Label("Save session", systemImage: "checkmark")
                }
            }
            .buttonStyle(PaperButtonStyle(kind: .primary))
            .disabled(isSaving || transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Discard") {
                coordinator.discard()
                onDone()
            }
            .buttonStyle(PaperButtonStyle(kind: .quiet))
            .disabled(isSaving)
        }
    }

    private func save() async {
        isSaving = true
        let client = coordinator.client
        var updated = session
        updated.transcript = transcript
        let saved = await coordinator.save(updated)
        if saved, let client {
            do {
                _ = try await app.memory.process(session: updated, client: client)
            } catch {
                app.notices.error("Session saved, but facts could not be extracted.")
            }
        }
        isSaving = false
        if saved {
            onDone()
        }
    }
}
