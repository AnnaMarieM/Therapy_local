import XCTest
@testable import MindVaultPerception

final class TurnPlannerTests: XCTestCase {
    private func turn(_ s: Int64, _ e: Int64, _ sp: String = "S0") -> TimedTurn {
        TimedTurn(startMs: s, endMs: e, speaker: sp)
    }

    private func plan(_ turns: [TimedTurn], duration: Int64 = 600_000) -> [TimedTurn] {
        TurnPlanner.plan(turns, durationMs: duration)
    }

    func testEmptyInputGivesNoPieces() {
        XCTAssertEqual([], plan([]))
    }

    func testOneSpeakerOneTurnIsKept() {
        XCTAssertEqual([turn(1000, 5000)], plan([turn(1000, 5000)]))
    }

    func testZeroAndNegativeLengthTurnsAreDropped() {
        XCTAssertEqual([turn(0, 3000, "S1")], plan([turn(500, 500), turn(900, 800), turn(0, 3000, "S1")]))
    }

    func testOutOfOrderTurnsAreSorted() {
        XCTAssertEqual([turn(0, 2000, "S0"), turn(3000, 6000, "S1")], plan([turn(3000, 6000, "S1"), turn(0, 2000, "S0")]))
    }

    func testOverlapIsCutAtTheEarlierEnd() {
        XCTAssertEqual([turn(0, 3000, "S0"), turn(3000, 6000, "S1")], plan([turn(0, 3000, "S0"), turn(2000, 6000, "S1")]))
    }

    func testATurnInsideAnotherIsDropped() {
        XCTAssertEqual([turn(0, 9000, "S0")], plan([turn(0, 9000, "S0"), turn(2000, 3000, "S1")]))
    }

    func testTurnsAreClampedToTheRecording() {
        XCTAssertEqual([turn(0, 4000)], plan([turn(-500, 9000)], duration: 4000))
    }

    func testAShortTurnIsAbsorbedByThePreviousOne() {
        XCTAssertEqual(
            [turn(0, 3300, "S0"), turn(3300, 8000, "S1")],
            plan([turn(0, 3000, "S0"), turn(3000, 3300, "S1"), turn(3300, 8000, "S1")])
        )
    }

    func testAShortFirstTurnJoinsTheNextOne() {
        XCTAssertEqual([turn(0, 5000, "S1")], plan([turn(0, 200, "S0"), turn(200, 5000, "S1")]))
    }

    func testALoneShortTurnIsDropped() {
        XCTAssertEqual([], plan([turn(0, 399)]))
    }

    func testATurnOfExactlyTheMinimumIsKept() {
        XCTAssertEqual([turn(0, 400)], plan([turn(0, 400)]))
    }

    func testSameSpeakerAcrossAShortInterjectionBecomesOnePiece() {
        XCTAssertEqual(
            [turn(0, 10_000, "S0")],
            plan([turn(0, 4000, "S0"), turn(4000, 4200, "S1"), turn(4200, 10_000, "S0")])
        )
    }

    func testSameSpeakerWithASmallGapIsJoinedButNotWithALargeOne() {
        XCTAssertEqual([turn(0, 5200)], plan([turn(0, 2000), turn(2299, 5200)]))
        XCTAssertEqual([turn(0, 2000), turn(2300, 5200)], plan([turn(0, 2000), turn(2300, 5200)]))
    }

    func testDifferentSpeakersAreNeverJoined() {
        XCTAssertEqual(2, plan([turn(0, 2000, "S0"), turn(2000, 5000, "S1")]).count)
    }

    func testALongPieceIsSplitIntoNearEqualParts() {
        let p = plan([turn(0, 50_000)])
        XCTAssertEqual([turn(0, 16_666), turn(16_666, 33_333), turn(33_333, 50_000)], p)
    }

    func testAPieceOfExactlyTheMaximumIsNotSplit() {
        XCTAssertEqual(1, plan([turn(0, TurnPlanner.maxPieceMs)]).count)
    }

    func testOneMsOverTheMaximumIsSplitInTwo() {
        XCTAssertEqual(2, plan([turn(0, TurnPlanner.maxPieceMs + 1)]).count)
    }

    func testTheCutChooserGetsARangeAroundTheEvenSplit() {
        var seen: (Int64, Int64)?
        let p = TurnPlanner.plan([turn(0, 30_000)], durationMs: 30_000) { s, e in
            seen = (s, e)
            return s
        }
        XCTAssertEqual(Int64(14_000), seen?.0)
        XCTAssertEqual(Int64(16_000), seen?.1)
        XCTAssertEqual([turn(0, 14_000), turn(14_000, 30_000)], p)
    }

    func testAMisbehavingChooserCannotBreakTheLimit() {
        for bad in [Int64(-5), 0, Int64.max] {
            let p = TurnPlanner.plan([turn(0, 70_000)], durationMs: 70_000) { _, _ in bad }
            XCTAssertTrue(p.allSatisfy { $0.endMs - $0.startMs >= 1 && $0.endMs - $0.startMs <= TurnPlanner.maxPieceMs })
            XCTAssertEqual(0, p.first?.startMs)
            XCTAssertEqual(70_000, p.last?.endMs)
            XCTAssertEqual(p.count, max(1, p.count))
            for (a, b) in zip(p, p.dropFirst()) {
                XCTAssertEqual(a.endMs, b.startMs)
            }
        }
    }

    /// Invariants only, not parity: `SeededGenerator` is xorshift while Kotlin's
    /// `Random(7)` is `XorWowRandom` with a different bounded-int algorithm, so these
    /// 500 cases are NOT the JVM's cases — what must hold everywhere is the invariant
    /// set below, and it does on both.
    func testRandomInputKeepsTheInvariants() {
        var rng = SeededGenerator(seed: 7)
        for _ in 0..<500 {
            let duration = 1 + Int64.random(in: 0..<300_000, using: &rng)
            let turns = (0..<Int.random(in: 0..<30, using: &rng)).map { _ -> TimedTurn in
                let s = Int64.random(in: -1000..<(duration + 1000), using: &rng)
                return turn(s, s + Int64.random(in: -100..<60_000, using: &rng), "S\(Int.random(in: 0..<3, using: &rng))")
            }
            let p = TurnPlanner.plan(turns, durationMs: duration)
            for x in p {
                XCTAssertTrue(x.startMs >= 0 && x.endMs <= duration)
                XCTAssertTrue(x.endMs - x.startMs >= 1 && x.endMs - x.startMs <= TurnPlanner.maxPieceMs)
            }
            for (a, b) in zip(p, p.dropFirst()) {
                XCTAssertTrue(a.endMs <= b.startMs)
            }
        }
    }
}

/// A tiny seeded generator, so the randomized tests are reproducible like Kotlin's Random(seed).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 6364136223846793005 &+ 1442695040888963407 }
    mutating func next() -> UInt64 {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return state
    }
}
