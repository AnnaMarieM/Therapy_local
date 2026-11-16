import SwiftUI
import MindVaultCore

struct SessionView: View {
    let client: Client
    let app: AppEnvironment
    @ObservedObject var coordinator: SessionCoordinator
    @ObservedObject var audio: AudioCaptureService
    @State private var showingDiscardConfirm = false

    init(client: Client, app: AppEnvironment) {
        self.client = client
        self.app = app
        _coordinator = ObservedObject(wrappedValue: app.sessions)
        _audio = ObservedObject(wrappedValue: app.audio)
    }

    var body: some View {
        PaperScreen {
            VStack(spacing: 0) {
                topBar
                    .padding(.horizontal, PaperSpacing.xl)
                    .padding(.top, PaperSpacing.sm)
                ScrollView {
                    VStack(alignment: .leading, spacing: PaperSpacing.xl) {
                        sessionHeader
                        waveform
                        cueSection
                        transcriptSection
                    }
                    .padding(.horizontal, PaperSpacing.xl)
                    .padding(.vertical, PaperSpacing.xl)
                }
                controls
                    .padding(.horizontal, PaperSpacing.xl)
                    .padding(.bottom, PaperSpacing.lg)
            }
        }
        .interactiveDismissDisabled(true)
        .overlay {
            if showingDiscardConfirm {
                PaperConfirmDialog(
                    title: "Discard this recording?",
                    message: "The audio and transcript will not be saved.",
                    confirmTitle: "Discard",
                    onConfirm: {
                        coordinator.discard()
                        app.router.sessionClient = nil
                    },
                    onCancel: { showingDiscardConfirm = false }
                )
            }
        }
        .accessibilityIdentifier("session.screen")
        .task {
            #if DEBUG
            ScreenshotHarness.seedSession(
                record: {
                    coordinator.screenshotSeed(
                        client: client,
                        elapsed: 1_325,
                        cues: ScreenshotHarness.recordingCue().map { [$0] } ?? [],
                        transcript: ""
                    )
                    audio.screenshotSeedLevel(0.45)
                },
                showDiscard: { showingDiscardConfirm = true }
            )
            #endif
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                if coordinator.isRecording {
                    showingDiscardConfirm = true
                } else {
                    coordinator.discard()
                    app.router.sessionClient = nil
                }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.tonal.opacity(0.45)))
            }
            .accessibilityLabel("Close session")
            Spacer()
            VStack(spacing: 2) {
                Text(client.displayName)
                    .paperText(PaperTypography.action)
                LabelText("Session")
            }
            Spacer()
            // A compact banner cannot fit the 44 pt slot here: its label wraps letter by
            // letter (visible in the CI screenshots, issue #133). The lock alone says it.
            Image(systemName: "lock.shield")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.inkSoft)
                .frame(width: 44, height: 44)
                .accessibilityLabel("On-device · encrypted")
        }
    }

    private var sessionHeader: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            Text(DurationFormat.clock(coordinator.elapsed))
                .paperText(PaperTypography.hero)
                .monospacedDigit()
                .accessibilityIdentifier("session.timer")
            HStack(spacing: PaperSpacing.sm) {
                if coordinator.phase == .recording {
                    PulseDot(color: .rec)
                    LabelText("Recording")
                } else if coordinator.phase == .paused {
                    LabelText("Paused")
                } else {
                    LabelText("Ready")
                }
                LabelText("On-device")
            }
            PrivacyBanner()
        }
    }

    private var waveform: some View {
        HStack(alignment: .center, spacing: 4) {
            ForEach(0..<28, id: \.self) { index in
                Capsule()
                    .fill(index.isMultiple(of: 3) ? Color.rec.opacity(0.8) : Color.ink.opacity(0.62))
                    .frame(width: 4, height: barHeight(index))
            }
        }
        .frame(maxWidth: .infinity, minHeight: 72)
        .animation(.easeOut(duration: 0.18), value: audio.level)
        .accessibilityHidden(true)
    }

    private func barHeight(_ index: Int) -> CGFloat {
        let pattern: [CGFloat] = [0.25, 0.6, 0.35, 0.8, 0.45, 1.0, 0.3, 0.7]
        let base = pattern[index % pattern.count]
        let energy = CGFloat(max(audio.level, 0.02)) * 220
        return max(4, min(64, base * energy))
    }

    private var cueSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Memory cues", detail: coordinator.cues.isEmpty ? nil : "\(coordinator.cues.count)")
            if coordinator.cues.isEmpty {
                Text("Related facts appear here as the conversation unfolds.")
                    .paperText(PaperTypography.bodySmall)
                    .foregroundStyle(Color.inkFaint)
            } else {
                ForEach(coordinator.cues.prefix(3)) { cue in
                    PaperCard(padding: PaperSpacing.md) {
                        VStack(alignment: .leading, spacing: PaperSpacing.xs) {
                            LabelText(cue.category.title)
                            Text(cue.text)
                                .paperText(PaperTypography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    private var transcriptSection: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.md) {
            SectionHeader(title: "Live transcript")
            Text(coordinator.liveTranscript.isEmpty ? "Transcript appears here as you speak." : coordinator.liveTranscript)
                .paperText(PaperTypography.body)
                .foregroundStyle(coordinator.liveTranscript.isEmpty ? Color.inkFaint : Color.inkSoft)
                .lineSpacing(4)
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch coordinator.phase {
        case .idle:
            VStack(alignment: .leading, spacing: PaperSpacing.md) {
                Toggle(isOn: $coordinator.consentConfirmed) {
                    Text("I have consent to record and process this session on-device.")
                        .paperText(PaperTypography.bodySmall)
                }
                .tint(Color.rev)
                Button {
                    Task { await coordinator.start(client: client) }
                } label: {
                    Label("Begin session", systemImage: "waveform")
                }
                .buttonStyle(PaperButtonStyle(kind: .record))
                .disabled(!coordinator.consentConfirmed)
                .accessibilityIdentifier("session.begin")
            }
        case .recording, .paused:
            HStack(alignment: .center, spacing: PaperSpacing.lg) {
                Button {
                    if coordinator.phase == .recording {
                        coordinator.pause()
                    } else {
                        coordinator.resume()
                    }
                } label: {
                    Image(systemName: coordinator.phase == .recording ? "pause.fill" : "play.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 64, height: 64)
                        .background(Circle().fill(Color.tonal.opacity(0.6)))
                }
                .accessibilityLabel(coordinator.phase == .recording ? "Pause" : "Resume")
                Spacer()
                HoldToFinishButton {
                    Task {
                        if let session = await coordinator.stop() {
                            app.router.finishSession(session)
                        }
                    }
                }
                Spacer()
                VStack(spacing: 3) {
                    LabelText("Elapsed")
                    Text(DurationFormat.minSec(coordinator.elapsed))
                        .paperText(PaperTypography.action)
                        .monospacedDigit()
                }
                .frame(width: 64)
            }
        case .finishing, .saving:
            HStack(spacing: PaperSpacing.md) {
                ProgressView()
                    .tint(Color.ink)
                Text("Preparing the session…")
                    .paperText(PaperTypography.body)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, PaperSpacing.lg)
        case .failed(let message):
            VStack(alignment: .leading, spacing: PaperSpacing.sm) {
                Text(message)
                    .paperText(PaperTypography.bodySmall)
                    .foregroundStyle(Color.rec)
                Button("Close") {
                    coordinator.discard()
                    app.router.sessionClient = nil
                }
                .buttonStyle(PaperButtonStyle(kind: .secondary))
            }
        }
    }
}
