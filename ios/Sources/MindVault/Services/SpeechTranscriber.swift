import AVFoundation
import Darwin
import Foundation
import MindVaultCore
import Speech

struct SpeechAvailability: Sendable {
    let onDevice: Bool
    let language: String
    let detail: String
}

enum SpeechAuthorizationStatus: String, Sendable {
    case notDetermined
    case denied
    case restricted
    case authorized
}

/// Frozen app contract: on-device only, no server fallback.
protocol SpeechTranscribing: Sendable {
    func availability(language: String) -> SpeechAvailability
    func transcribe(pcm: Data, sampleRate: Int, language: String) async throws -> TranscriptSegment
}

/// Additive contract for Settings/consent UI. Kept separate from `SpeechTranscribing` so
/// existing test doubles stay source-compatible.
protocol SpeechAuthorizationProviding: Sendable {
    func speechAuthorizationStatus() -> SpeechAuthorizationStatus
    func requestSpeechAuthorization() async -> SpeechAuthorizationStatus
}

final class OnDeviceSpeechTranscriber: SpeechTranscribing, SpeechAuthorizationProviding, @unchecked Sendable {
    static let defaultLanguage = "en-US"

    func availability(language: String) -> SpeechAvailability {
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else {
            return SpeechAvailability(onDevice: false, language: language, detail: "Locale unavailable")
        }
        guard recognizer.supportsOnDeviceRecognition else {
            return SpeechAvailability(
                onDevice: false,
                language: language,
                detail: "On-device speech assets are not installed for this language"
            )
        }
        guard recognizer.isAvailable else {
            return SpeechAvailability(onDevice: false, language: language, detail: "Recognizer unavailable")
        }
        return SpeechAvailability(onDevice: true, language: language, detail: "On-device recognition available")
    }

    func speechAuthorizationStatus() -> SpeechAuthorizationStatus {
        Self.map(SFSpeechRecognizer.authorizationStatus())
    }

    func requestSpeechAuthorization() async -> SpeechAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: Self.map(status))
            }
        }
    }

    func transcribe(pcm: Data, sampleRate: Int, language: String) async throws -> TranscriptSegment {
        guard !pcm.isEmpty, pcm.count >= 2 else {
            throw TranscriptionError.emptyAudio
        }
        guard speechAuthorizationStatus() == .authorized else {
            throw TranscriptionError.permissionDenied
        }

        let availability = availability(language: language)
        guard availability.onDevice else {
            throw TranscriptionError.onDeviceUnavailable
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else {
            throw TranscriptionError.onDeviceUnavailable
        }

        let durationSeconds = Double(pcm.count / 2) / Double(max(1, sampleRate))
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.taskHint = .dictation
        if #available(iOS 16.0, *) {
            request.addsPunctuation = true
        }

        guard let buffer = Self.makeBuffer(pcm: pcm, sampleRate: sampleRate) else {
            throw TranscriptionError.failed("Could not prepare the audio buffer.")
        }

        let box = RecognitionContinuation()
        let timeout = max(20.0, durationSeconds * 2.0 + 10.0)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                box.install(continuation)
                let recognition = recognizer.recognitionTask(with: request) { result, error in
                    if let result, result.isFinal {
                        let segment = TranscriptSegment(
                            chunkIndex: 0,
                            startMs: 0,
                            endMs: Int((durationSeconds * 1_000).rounded()),
                            text: result.bestTranscription.formattedString
                        )
                        box.resolve(.success(segment))
                    } else if let error {
                        box.resolve(.failure(Self.recognitionError(error)))
                    }
                }
                box.attach(recognition, request: request)
                request.append(buffer)
                request.endAudio()

                Task {
                    try? await Task.sleep(for: .milliseconds(Int(timeout * 1_000)))
                    box.resolve(.failure(TranscriptionError.failed("On-device recognition timed out.")))
                }
            }
        } onCancel: {
            box.cancel()
        }
    }

    private static func makeBuffer(pcm: Data, sampleRate: Int) -> AVAudioPCMBuffer? {
        let frames = pcm.count / 2
        guard frames > 0,
              let format = AVAudioFormat(
                  commonFormat: .pcmFormatInt16,
                  sampleRate: Double(sampleRate),
                  channels: 1,
                  interleaved: true
              ),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))
        else { return nil }

        buffer.frameLength = AVAudioFrameCount(frames)
        guard let destination = buffer.int16ChannelData?[0] else { return nil }
        pcm.withUnsafeBytes { raw in
            guard let source = raw.baseAddress else { return }
            memcpy(destination, source, frames * MemoryLayout<Int16>.size)
        }
        return buffer
    }

    private static func map(_ status: SFSpeechRecognizerAuthorizationStatus) -> SpeechAuthorizationStatus {
        switch status {
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .restricted: return .restricted
        case .authorized: return .authorized
        @unknown default: return .denied
        }
    }

    private static func recognitionError(_ error: Error) -> TranscriptionError {
        let nsError = error as NSError
        let detail = nsError.localizedDescription
        if detail.localizedCaseInsensitiveContains("on-device") ||
            detail.localizedCaseInsensitiveContains("offline") {
            return .onDeviceUnavailable
        }
        return .failed("\(detail) (\(nsError.domain) \(nsError.code))")
    }
}

/// Live on-device recognition fed with the same 16 kHz PCM16 blocks the capture service
/// emits. Partial strings arrive on `partials`; `finish()` returns the final text. Start
/// fails closed when authorization or on-device assets are missing.
protocol LiveSpeechTranscribing: AnyObject, Sendable {
    var partials: AsyncStream<String> { get }
    func start(language: String) throws
    func append(pcm: Data, sampleRate: Int)
    func finish() async -> String
    func cancel()
}

final class OnDeviceLiveTranscriber: LiveSpeechTranscribing, @unchecked Sendable {
    let partials: AsyncStream<String>

    private let continuation: AsyncStream<String>.Continuation
    private let lock = NSLock()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognition: SFSpeechRecognitionTask?
    private var finalContinuation: CheckedContinuation<String, Never>?
    private var bestText = ""
    private var finalText = ""
    private var isFinished = false
    private var isStarted = false
    private var streamFinished = false
    private var lastError: Error?
    private var timeoutTask: Task<Void, Never>?

    init() {
        let (stream, continuation) = AsyncStream<String>.makeStream(bufferingPolicy: .bufferingNewest(8))
        self.partials = stream
        self.continuation = continuation
    }

    deinit {
        timeoutTask?.cancel()
        recognition?.cancel()
        continuation.finish()
    }

    var error: Error? {
        lock.lock()
        defer { lock.unlock() }
        return lastError
    }

    func start(language: String) throws {
        guard OnDeviceSpeechTranscriber().speechAuthorizationStatus() == .authorized else {
            throw TranscriptionError.permissionDenied
        }
        let availability = OnDeviceSpeechTranscriber().availability(language: language)
        guard availability.onDevice else {
            throw TranscriptionError.onDeviceUnavailable
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else {
            throw TranscriptionError.onDeviceUnavailable
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        if #available(iOS 16.0, *) {
            request.addsPunctuation = true
        }

        lock.lock()
        guard !isStarted else {
            lock.unlock()
            throw TranscriptionError.failed("Live recognition is already running.")
        }
        self.recognizer = recognizer
        self.request = request
        isStarted = true
        lock.unlock()

        let task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            self?.handle(result: result, error: error)
        }
        lock.lock()
        recognition = task
        let finishedEarly = isFinished
        lock.unlock()
        if finishedEarly {
            task.cancel()
        }
    }

    func append(pcm: Data, sampleRate: Int) {
        guard !pcm.isEmpty else { return }
        guard let buffer = Self.makeBuffer(pcm: pcm, sampleRate: sampleRate) else { return }
        lock.lock()
        guard isStarted, !isFinished, let request else {
            lock.unlock()
            return
        }
        lock.unlock()
        request.append(buffer)
    }

    func finish() async -> String {
        let text: String = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            lock.lock()
            if isFinished || !isStarted {
                let value = finalText.isEmpty ? bestText : finalText
                let stream = streamFinished ? nil : self.continuation
                streamFinished = true
                lock.unlock()
                stream?.finish()
                continuation.resume(returning: value)
                return
            }
            finalContinuation = continuation
            let request = self.request
            let stream = streamFinished ? nil : self.continuation
            streamFinished = true
            lock.unlock()
            stream?.finish()
            timeoutTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(30))
                guard !Task.isCancelled else { return }
                self?.timeout()
            }
            request?.endAudio()
        }

        timeoutTask?.cancel()
        lock.lock()
        let remaining = recognition
        recognition = nil
        self.request = nil
        lock.unlock()
        remaining?.cancel()
        return text
    }

    func cancel() {
        timeoutTask?.cancel()
        lock.lock()
        isFinished = true
        streamFinished = true
        let final = finalContinuation
        finalContinuation = nil
        let request = self.request
        let task = recognition
        self.request = nil
        self.recognition = nil
        let stream = continuation
        let text = finalText.isEmpty ? bestText : finalText
        lock.unlock()
        task?.cancel()
        request?.endAudio()
        stream.finish()
        final?.resume(returning: text)
    }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?) {
        var partial: String?
        var completed: String?
        var failed: Error?
        var stream: AsyncStream<String>.Continuation?
        var final: CheckedContinuation<String, Never>?
        let finishStream: Bool

        lock.lock()
        if let result {
            let text = result.bestTranscription.formattedString
            if !text.isEmpty {
                bestText = text
                partial = text
            }
            if result.isFinal {
                finalText = text
                isFinished = true
                completed = text
            }
        }
        if let error, !isFinished {
            isFinished = true
            lastError = error
            failed = error
        }
        if isFinished {
            finishStream = !streamFinished
            streamFinished = true
            stream = finishStream ? continuation : nil
            final = finalContinuation
            finalContinuation = nil
        } else {
            finishStream = false
            stream = nil
        }
        lock.unlock()

        if let partial {
            continuation.yield(partial)
        }
        if let completed {
            final?.resume(returning: completed)
        } else if failed != nil {
            let text = bestText
            final?.resume(returning: text)
        }
        if finishStream {
            stream?.finish()
        }
    }

    private static func makeBuffer(pcm: Data, sampleRate: Int) -> AVAudioPCMBuffer? {
        let frames = pcm.count / 2
        guard frames > 0,
              let format = AVAudioFormat(
                  commonFormat: .pcmFormatInt16,
                  sampleRate: Double(sampleRate),
                  channels: 1,
                  interleaved: true
              ),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))
        else { return nil }
        buffer.frameLength = AVAudioFrameCount(frames)
        guard let destination = buffer.int16ChannelData?[0] else { return nil }
        pcm.withUnsafeBytes { raw in
            guard let source = raw.baseAddress else { return }
            memcpy(destination, source, frames * MemoryLayout<Int16>.size)
        }
        return buffer
    }

    private func timeout() {
        lock.lock()
        guard !isFinished, let final = finalContinuation else {
            lock.unlock()
            return
        }
        isFinished = true
        streamFinished = true
        finalContinuation = nil
        let text = bestText
        let stream = continuation
        let request = self.request
        lock.unlock()
        request?.endAudio()
        stream.finish()
        final.resume(returning: text)
    }
}

/// Thread-safe one-shot continuation for `SFSpeechRecognizer` callbacks.
private final class RecognitionContinuation: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<TranscriptSegment, Error>?
    private var result: Result<TranscriptSegment, Error>?
    private var task: SFSpeechRecognitionTask?
    private var request: SFSpeechAudioBufferRecognitionRequest?

    func install(_ continuation: CheckedContinuation<TranscriptSegment, Error>) {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(with: result)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func attach(_ task: SFSpeechRecognitionTask, request: SFSpeechAudioBufferRecognitionRequest) {
        lock.lock()
        self.task = task
        self.request = request
        let finished = result != nil
        lock.unlock()
        if finished {
            task.cancel()
        }
    }

    func resolve(_ value: Result<TranscriptSegment, Error>) {
        lock.lock()
        guard result == nil else {
            lock.unlock()
            return
        }
        result = value
        let continuation = self.continuation
        self.continuation = nil
        let task = self.task
        self.request = nil
        lock.unlock()
        continuation?.resume(with: value)
        if case .failure = value {
            task?.cancel()
        }
    }

    func cancel() {
        resolve(.failure(CancellationError()))
    }
}
