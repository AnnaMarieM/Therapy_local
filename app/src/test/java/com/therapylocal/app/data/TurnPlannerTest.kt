package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TurnPlannerTest {
    private fun t(s: Long, e: Long, sp: String = "S0") = TimedTurn(s, e, sp)
    private fun plan(vararg turns: TimedTurn, dur: Long = 600_000) = TurnPlanner.plan(turns.toList(), dur)

    @Test
    fun emptyInputGivesNoPieces() = assertEquals(emptyList<TimedTurn>(), plan())

    @Test
    fun oneSpeakerOneTurnIsKept() = assertEquals(listOf(t(1000, 5000)), plan(t(1000, 5000)))

    @Test
    fun zeroAndNegativeLengthTurnsAreDropped() = assertEquals(listOf(t(0, 3000, "S1")), plan(t(500, 500), t(900, 800), t(0, 3000, "S1")))

    @Test
    fun outOfOrderTurnsAreSorted() = assertEquals(listOf(t(0, 2000, "S0"), t(3000, 6000, "S1")), plan(t(3000, 6000, "S1"), t(0, 2000, "S0")))

    @Test
    fun overlapIsCutAtTheEarlierEnd() = assertEquals(listOf(t(0, 3000, "S0"), t(3000, 6000, "S1")), plan(t(0, 3000, "S0"), t(2000, 6000, "S1")))

    @Test
    fun aTurnInsideAnotherIsDropped() = assertEquals(listOf(t(0, 9000, "S0")), plan(t(0, 9000, "S0"), t(2000, 3000, "S1")))

    @Test
    fun turnsAreClampedToTheRecording() = assertEquals(listOf(t(0, 4000)), plan(t(-500, 9000), dur = 4000))

    @Test
    fun aShortTurnIsAbsorbedByThePreviousOne() =
        assertEquals(listOf(t(0, 3300, "S0"), t(3300, 8000, "S1")), plan(t(0, 3000, "S0"), t(3000, 3300, "S1"), t(3300, 8000, "S1")))

    @Test
    fun aShortFirstTurnJoinsTheNextOne() = assertEquals(listOf(t(0, 5000, "S1")), plan(t(0, 200, "S0"), t(200, 5000, "S1")))

    @Test
    fun aLoneShortTurnIsDropped() = assertEquals(emptyList<TimedTurn>(), plan(t(0, 399)))

    @Test
    fun aTurnOfExactlyTheMinimumIsKept() = assertEquals(listOf(t(0, 400)), plan(t(0, 400)))

    @Test
    fun sameSpeakerAcrossAShortInterjectionBecomesOnePiece() =
        assertEquals(listOf(t(0, 10_000, "S0")), plan(t(0, 4000, "S0"), t(4000, 4200, "S1"), t(4200, 10_000, "S0")))

    @Test
    fun sameSpeakerWithASmallGapIsJoinedButNotWithALargeOne() {
        assertEquals(listOf(t(0, 5200)), plan(t(0, 2000), t(2299, 5200)))
        assertEquals(listOf(t(0, 2000), t(2300, 5200)), plan(t(0, 2000), t(2300, 5200)))
    }

    @Test
    fun differentSpeakersAreNeverJoined() = assertEquals(2, plan(t(0, 2000, "S0"), t(2000, 5000, "S1")).size)

    @Test
    fun aLongPieceIsSplitIntoNearEqualParts() {
        val p = plan(t(0, 50_000))
        assertEquals(listOf(t(0, 16_666), t(16_666, 33_333), t(33_333, 50_000)), p)
    }

    @Test
    fun aPieceOfExactlyTheMaximumIsNotSplit() = assertEquals(1, plan(t(0, TurnPlanner.MAX_PIECE_MS)).size)

    @Test
    fun oneMsOverTheMaximumIsSplitInTwo() = assertEquals(2, plan(t(0, TurnPlanner.MAX_PIECE_MS + 1)).size)

    @Test
    fun theCutChooserGetsARangeAroundTheEvenSplit() {
        var seen: Pair<Long, Long>? = null
        val p = TurnPlanner.plan(listOf(t(0, 30_000)), 30_000) { s, e -> seen = s to e; s }
        assertEquals(14_000L to 16_000L, seen)
        assertEquals(listOf(t(0, 14_000), t(14_000, 30_000)), p)
    }

    @Test
    fun aMisbehavingChooserCannotBreakTheLimit() {
        for (bad in listOf(-5L, 0L, Long.MAX_VALUE)) {
            val p = TurnPlanner.plan(listOf(t(0, 70_000)), 70_000) { _, _ -> bad }
            assertTrue(p.all { it.endMs - it.startMs in 1..TurnPlanner.MAX_PIECE_MS })
            assertEquals(0L, p.first().startMs)
            assertEquals(70_000L, p.last().endMs)
            p.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
        }
    }

    @Test
    fun randomInputKeepsTheInvariants() {
        val rnd = Random(7)
        repeat(500) {
            val dur = 1 + rnd.nextLong(300_000)
            val turns = List(rnd.nextInt(0, 30)) {
                val s = rnd.nextLong(-1000, dur + 1000)
                t(s, s + rnd.nextLong(-100, 60_000), "S${rnd.nextInt(3)}")
            }
            val p = TurnPlanner.plan(turns, dur)
            for (x in p) {
                assertTrue(x.startMs >= 0 && x.endMs <= dur)
                assertTrue(x.endMs - x.startMs in 1..TurnPlanner.MAX_PIECE_MS)
            }
            p.zipWithNext { a, b -> assertTrue(a.endMs <= b.startMs) }
        }
    }
}
