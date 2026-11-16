import XCTest
@testable import MindVaultPerception

final class TranscriptPathTests: XCTestCase {
    private let twoHoursMs = Int64(2 * 3600) * 1000

    func testTooLongRecordingSkipsBeforeTheDiarizer() {
        XCTAssertEqual(.tooLong, TranscriptPath.skipBeforeDiarize(durationMs: twoHoursMs + 1, modelReady: true))
        // Exactly two hours fits (SpeakerDiarizer.MAX_SAMPLES).
        XCTAssertNil(TranscriptPath.skipBeforeDiarize(durationMs: twoHoursMs, modelReady: true))
        XCTAssertNil(TranscriptPath.skipBeforeDiarize(durationMs: 60_000, modelReady: true))
    }

    func testMissingModelSkipsBeforeTheDiarizer() {
        XCTAssertEqual(.modelMissing, TranscriptPath.skipBeforeDiarize(durationMs: 60_000, modelReady: false))
    }

    func testOneVoiceFallsBackOnlyInSpokenMode() {
        XCTAssertEqual(.oneSpeaker, TranscriptPath.skipAfterPlanning(speakers: ["S0"], mode: .spoken))
        XCTAssertEqual(.oneSpeaker, TranscriptPath.skipAfterPlanning(speakers: ["S0", "S0", "S0"], mode: .spoken))
        XCTAssertNil(TranscriptPath.skipAfterPlanning(speakers: ["S0", "S1"], mode: .spoken))
        // Fast and Quality work per turn whatever the speaker count.
        XCTAssertNil(TranscriptPath.skipAfterPlanning(speakers: ["S0"], mode: .listen))
        XCTAssertNil(TranscriptPath.skipAfterPlanning(speakers: ["S0"], mode: .englishOnly))
    }

    func testTheChunkPathBelongsToSpokenMode() {
        XCTAssertTrue(TurnMode.spoken.hasChunkFallback)
        XCTAssertFalse(TurnMode.listen.hasChunkFallback)
        XCTAssertFalse(TurnMode.englishOnly.hasChunkFallback)
    }

    func testPlannedTurnsLabelSpeakersAndCutQuietly() {
        let turns = [
            DiarTurn(startMs: 0, endMs: 10_000, speaker: 0),
            DiarTurn(startMs: 10_000, endMs: 20_000, speaker: 1),
        ]
        // Silence everywhere: the long turn is split at the even point.
        let pcm = [Int16](repeating: 0, count: 20 * 16_000)
        let planned = TranscriptPath.plannedTurns(turns, durationMs: 20_000, pcm: pcm)
        XCTAssertEqual(["S0", "S1"], planned.map(\.speaker))
        XCTAssertEqual(0, planned.first?.startMs)
        XCTAssertEqual(20_000, planned.last?.endMs)
        for (a, b) in zip(planned, planned.dropFirst()) {
            XCTAssertEqual(a.endMs, b.startMs)
        }
    }

    func testPiecesCarryTheTurnTimesAndSpeakers() {
        let turns = [TimedTurn(startMs: 0, endMs: 5_000, speaker: "S0"), TimedTurn(startMs: 5_000, endMs: 9_000, speaker: "S1")]
        let pieces = TranscriptPath.pieces(turns: turns, texts: ["hello", "there"])
        XCTAssertEqual(
            [Piece(startMs: 0, endMs: 5_000, text: "hello", speaker: "S0"),
             Piece(startMs: 5_000, endMs: 9_000, text: "there", speaker: "S1")],
            pieces
        )
    }

    func testChunkPiecesHaveNoSpeaker() {
        let pieces = TranscriptPath.chunkPieces([
            ChunkRow(recordingId: 1, idx: 0, startMs: 0, endMs: 20_000, overlapMs: 0, text: "one"),
            ChunkRow(recordingId: 1, idx: 1, startMs: 15_000, endMs: 30_000, overlapMs: 5_000, text: nil),
        ])
        XCTAssertEqual([nil, nil], pieces.map(\.speaker))
        XCTAssertEqual("one", pieces[0].text)
        XCTAssertEqual("", pieces[1].text)
    }
}
