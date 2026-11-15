import Foundation
import MindVaultCore

struct ChunkTranscript: Equatable, Sendable {
    let index: Int
    let startMs: Int
    let endMs: Int
    let overlapMs: Int
    let text: String
}

struct SessionTranscriptionResult: Equatable, Sendable {
    let transcript: String
    let segments: [TranscriptSegment]
    let chunks: [ChunkTranscript]
    let removedOverlapWords: Int
    let durationMs: Int

    var isEmpty: Bool {
        segments.isEmpty && transcript.isEmpty
    }

    /// Copy the transcription onto a session for review. Duration stays the pause-aware
    /// value measured by `SessionCoordinator`; chunk count follows the audio actually read.
    func applying(to session: Session) -> Session {
        var updated = session
        updated.transcript = transcript
        updated.segments = segments
        updated.chunkCount = chunks.count
        if updated.status == .recording || updated.status == .paused || updated.status == .transcribing {
            updated.status = .review
        }
        return updated
    }
}

/// Contract for the memory/job pipeline: read the session's encrypted pending chunks,
/// transcribe each in order, then clean/merge into a session transcript. On any throw the
/// encrypted audio is left untouched for a later retry (missing on-device assets, lock,
/// timeout). Implementations must never use server recognition.
protocol SessionTranscribing: Sendable {
    func transcribeSession(sessionID: UUID, language: String) async throws -> SessionTranscriptionResult
}

extension SessionTranscribing {
    func transcribeSession(sessionID: UUID) async throws -> SessionTranscriptionResult {
        try await transcribeSession(sessionID: sessionID, language: SessionTranscriptionService.defaultLanguage)
    }
}

struct SessionTranscriptionService: SessionTranscribing {
    static let defaultLanguage = "en-US"

    let store: any PendingAudioStore
    let transcriber: any SpeechTranscribing
    let logger: any AppLogger
    let clock: any Clock
    let language: String
    /// Called as `(completed, total)` after each chunk is transcribed.
    let progress: (@Sendable (Int, Int) -> Void)?

    init(
        store: any PendingAudioStore,
        transcriber: any SpeechTranscribing,
        logger: any AppLogger,
        clock: any Clock = SystemClock(),
        language: String = SessionTranscriptionService.defaultLanguage,
        progress: (@Sendable (Int, Int) -> Void)? = nil
    ) {
        self.store = store
        self.transcriber = transcriber
        self.logger = logger
        self.clock = clock
        self.language = language
        self.progress = progress
    }

    /// Convenience for callers that configured a language at construction time.
    func transcribeSession(sessionID: UUID) async throws -> SessionTranscriptionResult {
        try await transcribeSession(sessionID: sessionID, language: language)
    }

    func transcribeSession(sessionID: UUID, language: String) async throws -> SessionTranscriptionResult {
        let pending = try await store.readChunks(sessionID: sessionID)
        guard !pending.isEmpty else {
            return SessionTranscriptionResult(
                transcript: "",
                segments: [],
                chunks: [],
                removedOverlapWords: 0,
                durationMs: 0
            )
        }

        let ordered = pending.sorted { lhs, rhs in
            lhs.index == rhs.index ? lhs.startMs < rhs.startMs : lhs.index < rhs.index
        }

        var texts: [String] = []
        var chunks: [ChunkTranscript] = []
        texts.reserveCapacity(ordered.count)
        chunks.reserveCapacity(ordered.count)

        for (offset, chunk) in ordered.enumerated() {
            let segment = try await transcriber.transcribe(
                pcm: chunk.pcmData,
                sampleRate: ChunkMath.sampleRate,
                language: language
            )
            let cleaned = Self.clean(segment.text)
            texts.append(cleaned)
            chunks.append(
                ChunkTranscript(
                    index: chunk.index,
                    startMs: chunk.startMs,
                    endMs: chunk.endMs,
                    overlapMs: chunk.overlapMs,
                    text: cleaned
                )
            )
            progress?(offset + 1, ordered.count)
            logger.log(
                LogEntry(
                    level: .info,
                    category: "asr",
                    message: "chunk_transcribed index=\(chunk.index) chars=\(cleaned.count)"
                )
            )
        }

        let merged = TranscriptMerge.merge(texts)
        let segments = Self.segments(
            chunks: chunks,
            texts: texts,
            kept: merged.kept,
            sessionID: sessionID,
            createdAt: clock.now()
        )
        let removed = merged.removedWords.reduce(0, +)
        logger.log(
            LogEntry(
                level: .info,
                category: "asr",
                message: "session_transcribed chunks=\(chunks.count) segments=\(segments.count) words_removed=\(removed)"
            )
        )
        return SessionTranscriptionResult(
            transcript: merged.text,
            segments: segments,
            chunks: chunks,
            removedOverlapWords: removed,
            durationMs: chunks.map(\.endMs).max() ?? 0
        )
    }

    /// Deterministic cleanup: whitespace normalization plus prompt-echo removal, punctuation
    /// preserved. Same as the Android `SegmentBuilder`.
    static func clean(_ text: String) -> String {
        TranscriptClean.stripPromptEcho(text)
    }

    /// One timed segment per chunk that kept words after overlap merge. Times are the chunk's,
    /// narrowed to the kept word range assuming words are spread evenly across the chunk
    /// (the Android `SegmentBuilder` rule). Read in order, segment texts equal the merged
    /// transcript.
    static func segments(
        chunks: [ChunkTranscript],
        texts: [String],
        kept: [ClosedRange<Int>?],
        sessionID: UUID,
        createdAt: Date
    ) -> [TranscriptSegment] {
        chunks.indices.compactMap { index in
            guard index < texts.count, index < kept.count, let range = kept[index] else {
                return nil
            }
            let words = TranscriptMerge.words(texts[index])
            guard !words.isEmpty else { return nil }
            let lower = max(0, min(range.lowerBound, words.count - 1))
            let upper = max(lower, min(range.upperBound, words.count - 1))
            let chunk = chunks[index]
            let msPerWord = Double(max(0, chunk.endMs - chunk.startMs)) / Double(words.count)
            let startMs = chunk.startMs + Int(msPerWord * Double(lower))
            let endMs = chunk.startMs + Int(msPerWord * Double(upper + 1))
            let text = words[lower...upper].joined(separator: " ")
            guard !text.isEmpty else { return nil }
            return TranscriptSegment(
                sessionID: sessionID,
                chunkIndex: chunk.index,
                startMs: startMs,
                endMs: endMs,
                text: text,
                createdAt: createdAt
            )
        }
    }
}
