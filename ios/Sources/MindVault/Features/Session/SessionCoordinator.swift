import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class SessionCoordinator: ObservableObject {
    enum Phase: Equatable {
        case idle
        case recording
        case paused
        case finishing
        case saving
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var client: Client?
    @Published private(set) var sessionID: UUID?
    @Published private(set) var startedAt: Date?
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var liveTranscript = ""
    @Published private(set) var cues: [MemoryCue] = []
    @Published var consentConfirmed = false

    #if DEBUG
    /// Seeds a recording-in-progress for the CI screenshot harness (issue #133),
    /// without starting the ticker or any audio: the screen must stay static.
    /// Never called outside the DEBUG harness.
    func screenshotSeed(client: Client, elapsed: TimeInterval, cues: [MemoryCue], transcript: String) {
        self.client = client
        sessionID = UUID()
        startedAt = Date().addingTimeInterval(-elapsed)
        self.elapsed = elapsed
        phase = .recording
        self.cues = cues
        liveTranscript = transcript
    }
    #endif

    private let repository: any VaultRepository
    private let clock: any Clock
    private let audio: AudioCaptureService
    private let liveCues: LiveCueService
    private let notices: NoticeCenter
    private let logger: any AppLogger
    private var pauseState = PauseState()
    private var ticker: Task<Void, Never>?

    init(
        repository: any VaultRepository,
        clock: any Clock,
        audio: AudioCaptureService,
        liveCues: LiveCueService,
        notices: NoticeCenter,
        logger: any AppLogger
    ) {
        self.repository = repository
        self.clock = clock
        self.audio = audio
        self.liveCues = liveCues
        self.notices = notices
        self.logger = logger
    }

    var isRecording: Bool {
        phase == .recording || phase == .paused
    }

    @discardableResult
    func start(client: Client) async -> Bool {
        guard phase == .idle else { return false }
        guard consentConfirmed else {
            notices.info("Confirm consent before recording.")
            return false
        }
        guard await audio.requestPermission() else {
            notices.error("Microphone permission is required.")
            return false
        }
        do {
            try audio.start()
        } catch {
            notices.error(error.localizedDescription)
            return false
        }
        let now = clock.now()
        self.client = client
        self.sessionID = UUID()
        self.startedAt = now
        self.elapsed = 0
        self.liveTranscript = ""
        self.cues = []
        self.pauseState = PauseState()
        self.pauseState.start(at: now)
        self.phase = .recording
        self.liveCues.clear()
        startTicker()
        logger.log(LogEntry(level: .info, category: "session", message: "session_started"))
        return true
    }

    func pause() {
        guard phase == .recording else { return }
        audio.pause()
        pauseState.pause(at: clock.now())
        phase = .paused
    }

    func resume() {
        guard phase == .paused else { return }
        do {
            try audio.resume()
            pauseState.resume(at: clock.now())
            phase = .recording
        } catch {
            notices.error(error.localizedDescription)
        }
    }

    func stop() async -> Session? {
        guard isRecording, let client, let startedAt, let sessionID else { return nil }
        audio.stop()
        ticker?.cancel()
        ticker = nil
        let now = clock.now()
        let duration = pauseState.stop(at: now)
        phase = .finishing
        let session = Session(
            id: sessionID,
            clientID: client.id,
            externalID: IDs.sessionExternalID(client: client, date: startedAt),
            startedAt: startedAt,
            endedAt: now,
            duration: duration,
            chunkCount: ChunkMath.chunkCount(duration: duration),
            status: .review,
            transcript: liveTranscript,
            segments: []
        )
        logger.log(LogEntry(level: .info, category: "session", message: "session_stopped"))
        return session
    }

    @discardableResult
    func save(_ session: Session) async -> Bool {
        phase = .saving
        do {
            var saved = session
            saved.status = .saved
            try await repository.upsertSession(saved)
            notices.success("Session saved on this iPhone.")
            logger.log(LogEntry(level: .info, category: "session", message: "session_saved"))
            reset()
            return true
        } catch {
            phase = .failed(error.localizedDescription)
            notices.error(error.localizedDescription)
            return false
        }
    }

    func discard() {
        notices.info("Recording discarded.")
        reset()
    }

    func updateLiveTranscript(_ text: String) async {
        guard isRecording else { return }
        liveTranscript = text
        guard let client, let sessionID else { return }
        await liveCues.update(partialTranscript: text, client: client, sessionID: sessionID)
        cues = liveCues.cues
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self else { return }
                self.elapsed = self.pauseState.elapsed(at: self.clock.now())
            }
        }
    }

    private func reset() {
        ticker?.cancel()
        ticker = nil
        phase = .idle
        client = nil
        sessionID = nil
        startedAt = nil
        elapsed = 0
        liveTranscript = ""
        cues = []
        consentConfirmed = false
        pauseState = PauseState()
        liveCues.clear()
    }
}
