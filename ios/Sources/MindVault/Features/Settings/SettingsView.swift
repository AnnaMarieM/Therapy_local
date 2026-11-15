import SwiftUI
import MindVaultCore

struct SettingsView: View {
    let app: AppEnvironment
    @StateObject private var model: SettingsViewModel
    @Environment(\.dismiss) private var dismiss

    init(app: AppEnvironment) {
        self.app = app
        _model = StateObject(
            wrappedValue: SettingsViewModel(
                capabilities: app.capabilities,
                notifications: app.notifications,
                notices: app.notices
            )
        )
    }

    var body: some View {
        NavigationStack {
            PaperScreen {
                ScrollView {
                    VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                        privacySection
                        securitySection
                        capabilitiesSection
                        notificationSection
                        aboutSection
                    }
                    .padding(PaperSpacing.xl)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationBackground(Color.paper)
        .task { model.load() }
        .accessibilityIdentifier("settings.screen")
    }

    private var privacySection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Privacy")
            PaperCard {
                VStack(alignment: .leading, spacing: PaperSpacing.sm) {
                    HStack(spacing: PaperSpacing.sm) {
                        Image(systemName: "lock.shield")
                            .foregroundStyle(Color.rev)
                        Text("On-device only")
                            .paperText(PaperTypography.title)
                    }
                    Text("Audio, transcripts, facts, and search indexes stay on this iPhone. The app makes no network requests and bundles no model downloads.")
                        .paperText(PaperTypography.bodySmall)
                        .foregroundStyle(Color.inkSoft)
                    PrivacyBanner()
                }
            }
        }
    }

    private var securitySection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Security")
            PaperCard {
                VStack(alignment: .leading, spacing: PaperSpacing.md) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Vault")
                                .paperText(PaperTypography.action)
                            LabelText("AES-256-GCM · Keychain-wrapped key")
                        }
                        Spacer()
                        TagPill(text: "Unlocked", tint: .rev)
                    }
                    PaperDivider()
                    Button {
                        app.vault.lock()
                        dismiss()
                    } label: {
                        Label("Lock vault", systemImage: "lock")
                    }
                    .buttonStyle(PaperButtonStyle(kind: .secondary))
                }
            }
        }
    }

    private var capabilitiesSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Capabilities")
            if let report = model.report {
                PaperCard {
                    VStack(alignment: .leading, spacing: PaperSpacing.md) {
                        capabilityRow("On-device speech", ready: report.onDeviceSpeech, detail: report.speechDetail)
                        PaperDivider()
                        capabilityRow(
                            report.semanticEmbeddings ? "Sentence embeddings" : "Hashing embeddings",
                            ready: true,
                            detail: report.embeddingModel
                        )
                        PaperDivider()
                        capabilityRow("Biometrics", ready: report.biometrics, detail: report.biometrics ? "Available" : "Unavailable")
                        ForEach(report.notes, id: \.self) { note in
                            Text(note)
                                .paperText(PaperTypography.bodySmall)
                                .foregroundStyle(Color.inkFaint)
                        }
                    }
                }
            } else {
                ProgressView()
                    .tint(Color.ink)
            }
        }
    }

    private func capabilityRow(_ title: String, ready: Bool, detail: String) -> some View {
        HStack(alignment: .top, spacing: PaperSpacing.md) {
            Image(systemName: ready ? "checkmark.circle.fill" : "exclamationmark.circle")
                .foregroundStyle(ready ? Color.rev : Color.rec)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .paperText(PaperTypography.action)
                Text(detail)
                    .paperText(PaperTypography.bodySmall)
                    .foregroundStyle(Color.inkSoft)
            }
            Spacer()
        }
    }

    private var notificationSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Notifications")
            PaperCard {
                Toggle(isOn: Binding(
                    get: { model.notificationsEnabled },
                    set: { enabled in Task { await model.setNotificationsEnabled(enabled) } }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Session ready")
                            .paperText(PaperTypography.action)
                        Text("A local alert when processing finishes.")
                            .paperText(PaperTypography.bodySmall)
                            .foregroundStyle(Color.inkSoft)
                    }
                }
                .tint(Color.rev)
            }
        }
    }

    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            PaperDivider()
            HStack {
                Text(PaperMetadata.displayName)
                    .paperText(PaperTypography.bodySmall)
                Spacer()
                LabelText("Version \(PaperMetadata.marketingVersion)")
            }
            .foregroundStyle(Color.inkFaint)
        }
    }
}
