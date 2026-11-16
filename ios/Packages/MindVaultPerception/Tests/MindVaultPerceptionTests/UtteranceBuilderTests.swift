import XCTest
@testable import MindVaultPerception

final class UtteranceBuilderTests: XCTestCase {
    private func piece(_ s: Int64, _ e: Int64, _ text: String, _ sp: String? = "S0") -> Piece {
        Piece(startMs: s, endMs: e, text: text, speaker: sp)
    }

    func testEmptyInputGivesNothing() {
        XCTAssertEqual([], UtteranceBuilder.build([]))
    }

    func testNeighbouringPiecesOfOneSpeakerAreMerged() {
        let u = UtteranceBuilder.build([
            piece(0, 20_000, "one two"), piece(20_000, 30_000, "three"), piece(31_000, 40_000, "other", "S1"),
        ])
        XCTAssertEqual(
            [SegmentRow(idx: 1, startMs: 0, endMs: 30_000, text: "one two three", speaker: "S0"),
             SegmentRow(idx: 2, startMs: 31_000, endMs: 40_000, text: "other", speaker: "S1")],
            u
        )
    }

    func testASpeakerComingBackAfterAnotherIsANewUtterance() {
        let u = UtteranceBuilder.build([piece(0, 1, "a", "S0"), piece(1, 2, "b", "S1"), piece(2, 3, "c", "S0")])
        XCTAssertEqual([1, 2, 3], u.map(\.idx))
    }

    func testEmptyAndBlankTextsAreDroppedAndDoNotSeparateTheNeighbours() {
        let u = UtteranceBuilder.build([piece(0, 1, "a"), piece(1, 2, ""), piece(2, 3, "   "), piece(3, 4, "b")])
        XCTAssertEqual([SegmentRow(idx: 1, startMs: 0, endMs: 4, text: "a b", speaker: "S0")], u)
    }

    func testAllTextsEmptyGivesNothing() {
        XCTAssertEqual([], UtteranceBuilder.build([piece(0, 1, " "), piece(1, 2, "")]))
    }

    func testTextsAreTrimmed() {
        XCTAssertEqual("a b", UtteranceBuilder.build([piece(0, 1, "  a "), piece(1, 2, "\nb\n")]).only?.text)
    }

    func testUnknownSpeakersAreNotMerged() {
        let u = UtteranceBuilder.build([piece(0, 1, "a", nil), piece(1, 2, "b", nil)])
        XCTAssertEqual(
            [SegmentRow(idx: 1, startMs: 0, endMs: 1, text: "a", speaker: nil),
             SegmentRow(idx: 2, startMs: 1, endMs: 2, text: "b", speaker: nil)],
            u
        )
    }

    func testUnlabelledForgetsTheSpeakers() {
        let u = UtteranceBuilder.unlabelled([piece(0, 1, "a", "S0"), piece(1, 2, "b", "S0")])
        XCTAssertEqual([nil, nil], u.map(\.speaker))
        XCTAssertEqual(2, u.count)
    }

    func testOneSpeakerWholeRecordingIsOneUtterance() {
        let u = UtteranceBuilder.build((0..<5).map { piece(Int64($0) * 20_000, Int64($0 + 1) * 20_000, "w\($0)") })
        XCTAssertEqual([SegmentRow(idx: 1, startMs: 0, endMs: 100_000, text: "w0 w1 w2 w3 w4", speaker: "S0")], u)
    }

    func testEnglishJoinsByTheSameMerging() {
        let pieces = [
            Piece(startMs: 0, endMs: 20_000, text: "one two", speaker: "S0", textEn: "one two"),
            Piece(startMs: 20_000, endMs: 30_000, text: "three", speaker: "S0"),
            Piece(startMs: 30_000, endMs: 40_000, text: "four", speaker: "S1", textEn: " four "),
        ]
        XCTAssertEqual([1: "one two", 2: "four"], UtteranceBuilder.english(pieces))
        XCTAssertEqual([:], UtteranceBuilder.english([piece(0, 1, "a", "S0")]))
    }
}
