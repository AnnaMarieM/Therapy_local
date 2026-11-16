import SwiftUI
import MindVaultCore

struct VaultGateView: View {
    @ObservedObject var vault: VaultController
    @State private var password = ""
    @State private var confirmation = ""
    @State private var showingEraseConfirm = false

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                    brand
                    switch vault.state {
                    case .checking:
                        ProgressView()
                            .tint(Color.ink)
                            .frame(maxWidth: .infinity)
                    case .needsSetup:
                        setup
                    case .locked:
                        unlock
                    case .working:
                        working
                    case .unlocked:
                        EmptyView()
                    case .failed(let message):
                        failure(message)
                    }
                }
                .padding(.horizontal, PaperSpacing.xl)
                .padding(.top, PaperSpacing.xxl)
                .padding(.bottom, PaperSpacing.xxxl)
            }
        }
        .task {
            vault.refresh()
            #if DEBUG
            ScreenshotHarness.seedVaultGate(
                password: { password = $0 },
                confirmation: { confirmation = $0 },
                showErase: { showingEraseConfirm = true }
            )
            #endif
        }
        .overlay {
            if showingEraseConfirm {
                PaperConfirmDialog(
                    title: "Erase everything?",
                    message: "This deletes the encrypted vault and all local sessions. It cannot be undone.",
                    confirmTitle: "Erase",
                    onConfirm: {
                        showingEraseConfirm = false
                        Task { await vault.eraseAll() }
                    },
                    onCancel: { showingEraseConfirm = false }
                )
            }
        }
        .accessibilityIdentifier("vault.gate")
    }

    private var brand: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            HStack(spacing: PaperSpacing.sm) {
                Image(systemName: "waveform")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color.rec)
                LabelText("MindVault")
            }
            Text("A private room for the work.")
                .paperText(PaperTypography.statement)
            Text("Sessions, transcripts, and memory stay encrypted on this iPhone. Nothing is uploaded.")
                .paperText(PaperTypography.lead)
                .foregroundStyle(Color.inkSoft)
            PrivacyBanner()
        }
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            Text("Create your vault")
                .paperText(PaperTypography.title)
            Text("Choose a passphrase of at least eight characters. It protects the local encryption key.")
                .paperText(PaperTypography.body)
                .foregroundStyle(Color.inkSoft)
            PaperField(placeholder: "Passphrase", text: $password, isSecure: true)
                .accessibilityIdentifier("vault.password")
            PaperField(placeholder: "Confirm passphrase", text: $confirmation, isSecure: true)
            if let error = vault.errorMessage {
                errorText(error)
            }
            Button("Create vault") {
                guard password == confirmation else {
                    return
                }
                Task { await vault.createVault(password: password) }
            }
            .buttonStyle(PaperButtonStyle(kind: .primary))
            .disabled(!canSubmitSetup || vault.state == .working)
            .accessibilityIdentifier("vault.setup")
            if password != confirmation && !confirmation.isEmpty {
                errorText("Passphrases do not match.")
            }
        }
    }

    private var unlock: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            Text("Unlock")
                .paperText(PaperTypography.title)
            Text("Your vault is encrypted at rest. Enter your passphrase to open it.")
                .paperText(PaperTypography.body)
                .foregroundStyle(Color.inkSoft)
            PaperField(placeholder: "Passphrase", text: $password, isSecure: true)
                .accessibilityIdentifier("vault.password")
            if let error = vault.errorMessage {
                errorText(error)
            }
            Button("Unlock") {
                Task { await vault.unlock(password: password) }
            }
            .buttonStyle(PaperButtonStyle(kind: .primary))
            .disabled(password.isEmpty || vault.state == .working)
            .accessibilityIdentifier("vault.unlock")
            if vault.biometricAvailable {
                Button {
                    Task { await vault.unlockWithBiometrics() }
                } label: {
                    Label("Unlock with Face ID", systemImage: "faceid")
                }
                .buttonStyle(PaperButtonStyle(kind: .secondary))
                .disabled(vault.state == .working)
            }
            Button("Erase vault") {
                showingEraseConfirm = true
            }
            .buttonStyle(PaperButtonStyle(kind: .destructive))
            .padding(.top, PaperSpacing.lg)
        }
    }

    private var working: some View {
        HStack(spacing: PaperSpacing.md) {
            ProgressView()
                .tint(Color.ink)
            Text("Opening the vault…")
                .paperText(PaperTypography.body)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, PaperSpacing.xxl)
    }

    private func failure(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            Text("The vault could not open.")
                .paperText(PaperTypography.title)
            errorText(message)
            Button("Try again") {
                vault.retry()
            }
            .buttonStyle(PaperButtonStyle(kind: .secondary))
        }
    }

    private func errorText(_ message: String) -> some View {
        Text(message)
            .paperText(PaperTypography.bodySmall)
            .foregroundStyle(Color.rec)
    }

    private var canSubmitSetup: Bool {
        password.count >= 8 && password == confirmation
    }
}
