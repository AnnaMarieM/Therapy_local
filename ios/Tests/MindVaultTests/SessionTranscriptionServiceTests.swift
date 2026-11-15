import XCTest
import MindVaultCore
@testable import MindVault

final class SessionTranscriptionServiceTests: XCTestCase {
    func testServiceReadsInOrderCleansMergesAndTimestamps() async throws {
        let sessionID = UUID()
        let chunks = [
            PendingAudioChunk(index: 0, startMs: 0, endMs: 20_000, overlapMs: 0, pcmData: Data([0, 0])),
            PendingAudioChunk(index: 1, startMs: 15_000, endMs: 35_000, overlapMs: 5_000, pcmData: Data([1, 1]))
        ]
        let store = FakePendingAudioStore(chunks: chunks)
        let transcriber = SequencedTranscriber(texts: [
            "one two three four five six",
            "four five six seven eight nine"
        ])
        let service = SessionTranscriptionService(
            store: store,
            transcriber: transcriber,
            logger: NoopLogger()
        )

        let result = try await service.transcribeSession(sessionID: sessionID, language: "en-US")

        XCTAssertEqual(result.transcript, "one two three four five six seven eight nine")
        XCTAssertEqual(result.removedOverlapWords, 3)
        XCTAssertEqual(result.chunks.map(\.index), [0, 1])
        XCTAssertEqual(result.segments.count, 2)
        XCTAssertEqual(result.segments.map(\.chunkIndex), [0, 1])
        XCTAssertEqual(result.segments.map(\.text), ["one two three four", "five six seven eight nine"])
        XCTAssertEqual(result.segments.map(\.startMs), [0, 18_333])
        XCTAssertEqual(result.segments.map(\.endMs), [13_333, 35_000])
        XCTAssertEqual(result.segments.map(\.text).joined(separator: " "), result.transcript)
        XCTAssertEqual(result.durationMs, 35_000)
        XCTAssertTrue(result.segments.allSatisfy { $0.sessionID == sessionID })

        let clientID = UUID()
        let session = Session(
            id: sessionID,
            clientID: clientID,
            externalID: "synthetic_20261030-120000",
            status: .transcribing,
            transcript: "partial"
        )
        let updated = result.applying(to: session)
        XCTAssertEqual(updated.transcript, result.transcript)
        XCTAssertEqual(updated.segments, result.segments)
        XCTAssertEqual(updated.chunkCount, 2)
        XCTAssertEqual(updated.status, .review)
    }

    func testEmptyStoreReturnsEmptyResult() async throws {
        let service = SessionTranscriptionService(
            store: FakePendingAudioStore(chunks: []),
            transcriber: SequencedTranscriber(texts: []),
            logger: NoopLogger()
        )

        let result = try await service.transcribeSession(sessionID: UUID(), language: "en-US")

        XCTAssertTrue(result.isEmpty)
        XCTAssertEqual(result.durationMs, 0)
    }

    func testTranscriptionFailureKeepsPendingAudio() async throws {
        let sessionID = UUID()
        let store = FakePendingAudioStore(
            chunks: [PendingAudioChunk(index: 0, startMs: 0, endMs: 1_000, overlapMs: 0, pcmData: Data([0, 0]))]
        )
        let service = SessionTranscriptionService(
            store: store,
            transcriber: FailingTranscriber(),
            logger: NoopLogger()
        )

        do {
            _ = try await service.transcribeSession(sessionID: sessionID, language: "en-US")
            XCTFail("Expected on-device unavailable")
        } catch {
            XCTAssertEqual(error as? TranscriptionError, .onDeviceUnavailable)
        }

        let stillThere = try await store.hasPendingAudio(sessionID: sessionID)
        XCTAssertTrue(stillThere)
        let deleteCount = await store.deleteCount
        XCTAssertEqual(deleteCount, 0)
    }

    func testCleanRemovesPromptEchoAndNormalizesWhitespace() {
        let raw = "Hello there.  Transcribe the speech in this audio verbatim. Output only the transcript, nothing else.  How are you?"
        XCTAssertEqual(SessionTranscriptionService.clean(raw), "Hello there. How are you?")
    }

    func testSegmentTimesNarrowToKeptWords() {
        let chunks = [
            ChunkTranscript(index: 0, startMs: 0, endMs: 10_000, overlapMs: 0, text: "one two three four"),
            ChunkTranscript(index: 1, startMs: 5_000, endMs: 15_000, overlapMs: 5_000, text: "three four five six")
        ]
        let texts = chunks.map(\.text)
        let merged = TranscriptMerge.merge(texts)
        let segments = SessionTranscriptionService.segments(
            chunks: chunks,
            texts: texts,
            kept: merged.kept,
            sessionID: UUID(),
            createdAt: Date(timeIntervalSince1970: 0)
        )

        XCTAssertEqual(segments.map(\.text).joined(separator: " "), merged.text)
        XCTAssertTrue(segments.allSatisfy { $0.startMs >= 0 && $0.endMs <= 15_000 && $0.startMs < $0.endMs })
    }
}

private actor FakePendingAudioStore: PendingAudioStore {
    private let chunks: [PendingAudioChunk]
    private(set) var deleteCount = 0

    init(chunks: [PendingAudioChunk]) {
        self.chunks = chunks
    }

    func write(_ chunk: PendingAudioChunk, sessionID: UUID) async throws {}

    func readChunks(sessionID: UUID) async throws -> [PendingAudioChunk] {
        chunks
    }

    func hasPendingAudio(sessionID: UUID) async throws -> Bool {
        !chunks.isEmpty
    }

    func delete(sessionID: UUID) async throws {
        deleteCount += 1
    }
}

private final class SequenceBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        let current = value
        value += 1
        return current
    }
}

private struct SequencedTranscriber: SpeechTranscribing {
    let texts: [String]
    private let box = SequenceBox()

    init(texts: [String]) {
        self.texts = texts
    }

    func availability(language: String) -> SpeechAvailability {
        SpeechAvailability(onDevice: true, language: language, detail: "test")
    }

    func transcribe(pcm: Data, sampleRate: Int, language: String) async throws -> TranscriptSegment {
        let index = box.next()
        guard index < texts.count else {
            throw TranscriptionError.failed("no fixture text")
        }
        return TranscriptSegment(chunkIndex: index, startMs: 0, endMs: 1, text: texts[index])
    }
}

private struct FailingTranscriber: SpeechTranscribing {
    func availability(language: String) -> SpeechAvailability {
        SpeechAvailability(onDevice: false, language: language, detail: "unavailable")
    }

    func transcribe(pcm: Data, sampleRate: Int, language: String) async throws -> TranscriptSegment {
        throw TranscriptionError.onDeviceUnavailable
    }
}
