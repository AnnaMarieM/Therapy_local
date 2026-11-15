import AVFoundation
import Combine
import Foundation
import MindVaultCore

enum MicrophonePermission: String, Sendable {
    case undetermined
    case denied
    case granted
}

/// Captures the microphone with AVAudioEngine, converts to 16 kHz mono PCM16, cuts
/// `ChunkMath` chunks (20 s / 5 s overlap / 15 s step) and seals every closed chunk
/// through the injected `PendingAudioStore`. Only the current chunk and its 5 s overlap
/// tail stay in memory; nothing is written as plaintext.
@MainActor
final class AudioCaptureService: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var isPaused = false
    @Published private(set) var level: Float = 0
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var startedAt: Date?
    @Published private(set) var sessionID: UUID?
    @Published private(set) var savedChunkCount = 0
    @Published private(set) var interruptionActive = false
    @Published private(set) var lastError: String?

    /// Called on a background queue as each chunk closes, before the encrypted write.
    /// Hop to the main actor before touching UI state.
    var onChunkFinalized: (@Sendable (FinalizedAudioChunk) -> Void)?
    /// Called on a background queue with each converted 16 kHz mono PCM16 block, for a
    /// live on-device recognition session. The handler must be fast and must not retain
    /// the block after returning.
    var onLivePCM: (@Sendable (Data, Int) -> Void)? {
        didSet { processor.setLiveHandler(onLivePCM) }
    }
    /// Called on the main actor when capture or encrypted persistence fails.
    var onCaptureError: (@MainActor (Error) -> Void)?
    /// Called when an interruption or failed route recovery pauses capture. The coordinator must pause its own
    /// `PauseState` without calling `pause()` again (that would mark the pause as user
    /// initiated and suppress auto-resume).
    var onAutoPause: (@MainActor () -> Void)?
    /// Called after an interruption ends and the engine has resumed successfully.
    var onAutoResume: (@MainActor () -> Void)?

    private let logger: any AppLogger
    private let pendingAudioStore: (any PendingAudioStore)?
    private let clock: any Clock
    private let engine = AVAudioEngine()
    private let processor: AudioTapProcessor
    private var tapInstalled = false
    private var observerTokens: [NSObjectProtocol] = []
    private var ticker: Task<Void, Never>?
    private var chunkContinuation: AsyncStream<FinalizedAudioChunk>.Continuation?
    private var chunkWriter: Task<Void, Never>?
    private var pauseState = PauseState()
    private var userPaused = false
    private var interruptionPaused = false
    private var recoveryTask: Task<Void, Never>?
    private var suppressRecoveryUntil = Date.distantPast
    private var reportedMissingStore = false

    init(
        logger: any AppLogger,
        pendingAudioStore: (any PendingAudioStore)? = nil,
        clock: any Clock = SystemClock()
    ) {
        self.logger = logger
        self.pendingAudioStore = pendingAudioStore
        self.clock = clock
        self.processor = AudioTapProcessor()
    }

    deinit {
        for token in observerTokens {
            NotificationCenter.default.removeObserver(token)
        }
    }

    var microphonePermission: MicrophonePermission {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return .granted
        case .denied: return .denied
        case .undetermined: return .undetermined
        @unknown default: return .denied
        }
    }

    /// Binds the pending-audio session before `start()`. The encrypted writer captures this
    /// ID when capture starts, so the integrator must call this first. `SessionCoordinator`
    /// currently creates its session ID after `audio.start()`; ui-01 should reorder that.
    func bindSession(_ sessionID: UUID) {
        self.sessionID = sessionID
    }

    func unbindSession() {
        sessionID = nil
    }

    func requestPermission() async -> Bool {
        if microphonePermission == .granted { return true }
        let granted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        if !granted {
            logger.log(LogEntry(level: .warning, category: "audio", message: "microphone_permission_denied"))
        }
        return granted
    }

    func start(sessionID: UUID? = nil) throws {
        guard !isRecording else { throw AudioCaptureError.alreadyRunning }
        if let sessionID {
            self.sessionID = sessionID
        }
        guard microphonePermission == .granted else {
            throw AudioCaptureError.permissionDenied
        }

        do {
            try activateSession()
        } catch {
            throw AudioCaptureError.configurationFailed(error.localizedDescription)
        }

        processor.reset()
        processor.setLevelHandler { [weak self] value in
            Task { @MainActor [weak self] in
                self?.level = value
            }
        }
        do {
            try installTap()
            engine.prepare()
            try engine.start()
        } catch {
            tearDownEngine()
            deactivateSession()
            throw AudioCaptureError.configurationFailed(error.localizedDescription)
        }

        lastError = nil
        reportedMissingStore = false
        savedChunkCount = 0
        interruptionActive = false
        interruptionPaused = false
        userPaused = false
        suppressRecoveryUntil = .distantPast
        isRecording = true
        isPaused = false
        level = 0
        elapsed = 0
        startedAt = clock.now()
        pauseState = PauseState()
        pauseState.start(at: startedAt ?? clock.now())
        startChunkWriter()
        startTicker()
        installObservers()
        if pendingAudioStore == nil || self.sessionID == nil {
            reportMissingStoreOnce()
        }
        logger.log(LogEntry(level: .info, category: "audio", message: "capture_started"))
    }

    func pause() {
        guard isRecording, !isPaused else { return }
        userPaused = true
        interruptionPaused = false
        processor.setPaused(true)
        engine.pause()
        pauseState.pause(at: clock.now())
        isPaused = true
        level = 0
        logger.log(LogEntry(level: .info, category: "audio", message: "capture_paused"))
    }

    func resume() throws {
        guard isRecording, isPaused else { return }
        do {
            try activateSession()
            try startEngineAfterPause()
        } catch {
            lastError = error.localizedDescription
            throw AudioCaptureError.configurationFailed(error.localizedDescription)
        }
        userPaused = false
        interruptionPaused = false
        interruptionActive = false
        processor.setPaused(false)
        pauseState.resume(at: clock.now())
        isPaused = false
        logger.log(LogEntry(level: .info, category: "audio", message: "capture_resumed"))
    }

    func stop() {
        guard isRecording else { return }
        ticker?.cancel()
        ticker = nil
        recoveryTask?.cancel()
        recoveryTask = nil
        removeObservers()
        tearDownEngine()
        deactivateSession()

        elapsed = pauseState.stop(at: clock.now())
        isRecording = false
        isPaused = false
        userPaused = false
        interruptionActive = false
        interruptionPaused = false
        level = 0
        processor.setLevelHandler(nil)

        let continuation = chunkContinuation
        chunkContinuation = nil
        processor.stop { [weak self] in
            continuation?.finish()
            Task { @MainActor [weak self] in
                self?.logger.log(LogEntry(level: .info, category: "audio", message: "capture_stopped"))
            }
        }
        logger.log(LogEntry(level: .info, category: "audio", message: "capture_stopping"))
    }

    /// Stops capture and waits for the final chunk to be encrypted on disk. The memory/job
    /// pipeline can then read the complete pending-audio set for the session.
    func stopAndFlush() async {
        stop()
        await chunkWriter?.value
    }

    private func installTap() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw AudioCaptureError.configurationFailed("No microphone input format")
        }
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { [processor] buffer, _ in
            processor.handle(buffer)
        }
        tapInstalled = true
    }

    private func tearDownEngine() {
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        engine.stop()
    }

    private func activateSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .measurement,
            options: [.allowBluetooth, .defaultToSpeaker]
        )
        try session.setActive(true)
    }

    private func deactivateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// After a pause or interruption the tap format may have changed (route change while
    /// paused). Try the existing engine first, then reinstall the tap once.
    private func startEngineAfterPause() throws {
        if engine.isRunning { return }
        if !tapInstalled {
            processor.invalidateConverter()
            try installTap()
            engine.prepare()
        }
        do {
            try engine.start()
        } catch {
            tearDownEngine()
            processor.invalidateConverter()
            try installTap()
            engine.prepare()
            try engine.start()
        }
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, self.isRecording else { return }
                self.elapsed = self.pauseState.elapsed(at: self.clock.now())
            }
        }
    }

    private func startChunkWriter() {
        let store = pendingAudioStore
        let session = sessionID
        let (stream, continuation) = AsyncStream<FinalizedAudioChunk>.makeStream(
            bufferingPolicy: .unbounded
        )
        chunkContinuation = continuation
        processor.setChunkHandler { chunk in
            continuation.yield(chunk)
            Task { @MainActor [weak self] in
                self?.onChunkFinalized?(chunk)
            }
        }

        chunkWriter = Task.detached(priority: .utility) { [weak self] in
            for await chunk in stream {
                guard let store, let session else {
                    await MainActor.run { [weak self] in
                        self?.reportMissingStoreOnce()
                    }
                    continue
                }
                do {
                    let pending = PendingAudioChunk(
                        index: chunk.index,
                        startMs: chunk.startMs,
                        endMs: chunk.endMs,
                        overlapMs: chunk.overlapMs,
                        pcmData: chunk.pcm16
                    )
                    try await store.write(pending, sessionID: session)
                    await MainActor.run { [weak self] in
                        guard let self else { return }
                        self.savedChunkCount += 1
                        self.logger.log(
                            LogEntry(
                                level: .info,
                                category: "audio",
                                message: "chunk_sealed index=\(chunk.index)"
                            )
                        )
                    }
                } catch {
                    await MainActor.run { [weak self] in
                        self?.report(error)
                    }
                }
            }
        }
    }

    private func reportMissingStoreOnce() {
        guard !reportedMissingStore else { return }
        reportedMissingStore = true
        lastError = "Audio chunks are not being saved: the pending-audio store or session ID is missing."
        logger.log(
            LogEntry(
                level: .warning,
                category: "audio",
                message: "chunk_not_persisted missing_store_or_session"
            )
        )
    }

    private func report(_ error: Error) {
        let message = error.localizedDescription
        lastError = message
        logger.log(LogEntry(level: .error, category: "audio", message: "audio_error \(message)"))
        onCaptureError?(error)
    }

    // MARK: - Session notifications

    private func installObservers() {
        removeObservers()
        let center = NotificationCenter.default
        observerTokens.append(
            center.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                Task { @MainActor [weak self] in
                    self?.handleInterruption(notification)
                }
            }
        )
        observerTokens.append(
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                Task { @MainActor [weak self] in
                    self?.handleRouteChange(notification)
                }
            }
        )
        observerTokens.append(
            center.addObserver(
                forName: .AVAudioEngineConfigurationChange,
                object: engine,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.scheduleRecovery(reason: "engine_configuration")
                }
            }
        )
        observerTokens.append(
            center.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.scheduleRecovery(reason: "media_services_reset")
                }
            }
        )
    }

    private func removeObservers() {
        for token in observerTokens {
            NotificationCenter.default.removeObserver(token)
        }
        observerTokens.removeAll()
    }

    private func handleInterruption(_ notification: Notification) {
        guard
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: raw)
        else { return }

        switch type {
        case .began:
            guard isRecording, !userPaused, !interruptionPaused else { return }
            interruptionPaused = true
            interruptionActive = true
            processor.setPaused(true)
            engine.pause()
            pauseState.pause(at: clock.now())
            isPaused = true
            level = 0
            logger.log(LogEntry(level: .info, category: "audio", message: "interruption_began"))
            onAutoPause?()
        case .ended:
            guard interruptionPaused else { return }
            interruptionPaused = false
            interruptionActive = false
            let rawOptions = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions)
            guard options.contains(.shouldResume) else {
                logger.log(LogEntry(level: .info, category: "audio", message: "interruption_ended_no_resume"))
                return
            }
            do {
                try activateSession()
                try startEngineAfterPause()
                processor.setPaused(false)
                pauseState.resume(at: clock.now())
                isPaused = false
                logger.log(LogEntry(level: .info, category: "audio", message: "interruption_resumed"))
                onAutoResume?()
            } catch {
                report(AudioCaptureError.configurationFailed(error.localizedDescription))
            }
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard isRecording else { return }
        let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
            .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
        logger.log(
            LogEntry(
                level: .info,
                category: "audio",
                message: "route_changed reason=\(reason?.rawValue ?? 0)"
            )
        )
        scheduleRecovery(reason: "route_change")
    }

    /// A route or engine change can invalidate the tap format. Reinstall the tap with the
    /// current input format and restart the engine. The sample-based assembler timeline is
    /// preserved, so this never duplicates audio; the interruption itself may lose a few
    /// milliseconds of microphone audio while the engine restarts.
    private func scheduleRecovery(reason: String) {
        guard isRecording, !isPaused, !interruptionPaused else { return }
        guard Date() >= suppressRecoveryUntil else { return }
        recoveryTask?.cancel()
        recoveryTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard let self, !Task.isCancelled, self.isRecording, !self.isPaused else { return }
            self.recoverEngine(reason: reason)
        }
    }

    private func recoverEngine(reason: String) {
        guard isRecording, !isPaused else { return }
        // Restarting the engine can itself post a configuration change; do not loop on it.
        suppressRecoveryUntil = Date().addingTimeInterval(1)
        logger.log(LogEntry(level: .info, category: "audio", message: "capture_recovery \(reason)"))
        tearDownEngine()
        processor.invalidateConverter()
        do {
            try activateSession()
            try installTap()
            engine.prepare()
            try engine.start()
        } catch {
            processor.setPaused(true)
            pauseState.pause(at: clock.now())
            isPaused = true
            level = 0
            onAutoPause?()
            report(AudioCaptureError.configurationFailed(error.localizedDescription))
        }
    }
}

/// The non-main half of capture: converts the tap buffer on the audio thread, then cuts
/// chunks and emits callbacks on a serial queue. It holds only the current chunk plus the
/// 5 s overlap tail (about 800 KB worst case including the emitted copy).
private final class AudioTapProcessor: @unchecked Sendable {
    private let outputFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: Double(ChunkMath.sampleRate),
        channels: 1,
        interleaved: true
    )!
    private let queue = DispatchQueue(
        label: "com.therapylocal.mindvault.audio.chunks",
        qos: .userInitiated
    )
    private let lock = NSLock()
    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?
    private var accepting = false
    private var assembler = AudioChunkAssembler()

    private let handlerLock = NSLock()
    private var chunkHandler: (@Sendable (FinalizedAudioChunk) -> Void)?
    private var liveHandler: (@Sendable (Data, Int) -> Void)?
    private var levelHandler: (@Sendable (Float) -> Void)?
    private var lastLevelUptime: UInt64 = 0

    func setChunkHandler(_ handler: (@Sendable (FinalizedAudioChunk) -> Void)?) {
        handlerLock.lock()
        chunkHandler = handler
        handlerLock.unlock()
    }

    func setLiveHandler(_ handler: (@Sendable (Data, Int) -> Void)?) {
        handlerLock.lock()
        liveHandler = handler
        handlerLock.unlock()
    }

    func setLevelHandler(_ handler: (@Sendable (Float) -> Void)?) {
        handlerLock.lock()
        levelHandler = handler
        handlerLock.unlock()
    }

    /// Drop any buffered converter state and start a fresh assembler. Blocks until prior
    /// queued work (including a previous final chunk) has drained.
    func reset() {
        lock.lock()
        accepting = false
        converter = nil
        inputFormat = nil
        lock.unlock()
        queue.sync {
            assembler = AudioChunkAssembler()
            lastLevelUptime = 0
        }
        lock.lock()
        accepting = true
        lock.unlock()
    }

    func setPaused(_ paused: Bool) {
        lock.lock()
        accepting = !paused
        if paused {
            converter?.reset()
        }
        lock.unlock()
    }

    func invalidateConverter() {
        lock.lock()
        converter?.reset()
        converter = nil
        inputFormat = nil
        lock.unlock()
    }

    func handle(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        guard accepting, let pcm = convertLocked(buffer) else {
            lock.unlock()
            return
        }
        lock.unlock()

        queue.async { [weak self] in
            guard let self else { return }
            let now = DispatchTime.now().uptimeNanoseconds
            if now &- self.lastLevelUptime >= 33_000_000 {
                self.lastLevelUptime = now
                self.handlerLock.lock()
                let level = self.levelHandler
                self.handlerLock.unlock()
                level?(PCM16.rms(of: pcm))
            }

            self.handlerLock.lock()
            let live = self.liveHandler
            self.handlerLock.unlock()
            live?(pcm, ChunkMath.sampleRate)

            let closed = self.assembler.append(pcm16: pcm)
            guard !closed.isEmpty else { return }
            self.handlerLock.lock()
            let chunkHandler = self.chunkHandler
            self.handlerLock.unlock()
            for chunk in closed {
                chunkHandler?(chunk)
            }
        }
    }

    /// Stop accepting audio, flush the trailing chunk on the serial queue, then call
    /// `completion` from that queue. The audio service finishes its chunk stream there.
    func stop(completion: (@Sendable () -> Void)? = nil) {
        lock.lock()
        accepting = false
        converter?.reset()
        converter = nil
        inputFormat = nil
        lock.unlock()

        queue.async { [weak self] in
            guard let self else {
                completion?()
                return
            }
            if let final = self.assembler.finish() {
                self.handlerLock.lock()
                let handler = self.chunkHandler
                self.handlerLock.unlock()
                handler?(final)
            }
            completion?()
        }
    }

    private func convertLocked(_ buffer: AVAudioPCMBuffer) -> Data? {
        let sourceFormat = buffer.format
        if converter == nil || inputFormat?.isEqual(sourceFormat) != true {
            converter = AVAudioConverter(from: sourceFormat, to: outputFormat)
            inputFormat = sourceFormat
        }
        guard let converter else { return nil }

        let ratio = outputFormat.sampleRate / sourceFormat.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 1_024
        guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else {
            return nil
        }

        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, conversionError == nil else { return nil }
        guard output.frameLength > 0, let samples = output.int16ChannelData?[0] else {
            return nil
        }
        return Data(bytes: samples, count: Int(output.frameLength) * MemoryLayout<Int16>.size)
    }
}
