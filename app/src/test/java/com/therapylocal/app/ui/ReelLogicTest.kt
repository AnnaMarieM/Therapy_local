package com.therapylocal.app.ui

import com.therapylocal.app.memory.Cue
import com.therapylocal.app.ui.logic.CueView
import com.therapylocal.app.ui.logic.ReelIndex
import com.therapylocal.app.ui.logic.greeting
import com.therapylocal.app.ui.logic.heroShrink
import com.therapylocal.app.ui.logic.railLetterAt
import com.therapylocal.app.ui.logic.railLetterY
import com.therapylocal.app.ui.logic.sessionDate
import com.therapylocal.app.ui.logic.splitName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReelLogicTest {

    // ---- index ----

    @Test
    fun namesSortAlphabeticallyIgnoringCaseAndAccents() {
        val names = listOf("zoe K.", "Émile B.", "anna P.", "Eva L.", "Ben C.")
        val ix = ReelIndex(names, Locale.ENGLISH)
        assertEquals(listOf("anna P.", "Ben C.", "Émile B.", "Eva L.", "zoe K."), ix.order.map { names[it] })
        assertEquals(listOf("A", "B", "E", "Z"), ix.letters)
        assertEquals(listOf(0, 1, 2, 4), ix.firstOf)
        assertEquals(listOf(0, 1, 2, 2, 3), ix.letterOf)
    }

    @Test
    fun nonLettersGroupUnderHashFirst() {
        val ix = ReelIndex(listOf("Mia", "3 Test", "Ada"), Locale.ENGLISH)
        assertEquals("#", ix.letters.first())
        assertEquals(listOf("#", "A", "M"), ix.letters)
    }

    @Test
    fun emptyIndex() {
        val ix = ReelIndex(emptyList())
        assertEquals(0, ix.size)
        assertTrue(ix.letters.isEmpty())
    }

    // ---- rail: absolute, never reversed ----

    @Test
    fun railPicksTheLetterUnderTheFinger() {
        // 4 letters over 400 px starting at 100
        assertEquals(0, railLetterAt(100f, 100f, 400f, 4))
        assertEquals(0, railLetterAt(199f, 100f, 400f, 4))
        assertEquals(1, railLetterAt(200f, 100f, 400f, 4))
        assertEquals(3, railLetterAt(499f, 100f, 400f, 4))
    }

    @Test
    fun railClampsPastTheEnds() {
        assertEquals(0, railLetterAt(-50f, 100f, 400f, 4))
        assertEquals(3, railLetterAt(9000f, 100f, 400f, 4))
        assertEquals(0, railLetterAt(300f, 100f, 400f, 1))
        assertEquals(0, railLetterAt(300f, 100f, 0f, 5))
    }

    @Test
    fun movingDownTheRailNeverGoesBackInTheAlphabet() {
        var last = -1
        var y = 0f
        while (y < 700f) {
            val i = railLetterAt(y, 100f, 400f, 7)
            assertTrue("went back at $y", i >= last)
            last = i
            y += 0.5f
        }
        assertEquals(6, last)
    }

    @Test
    fun letterCentresMapBackToTheirLetter() {
        for (i in 0 until 9) assertEquals(i, railLetterAt(railLetterY(i, 50f, 360f, 9), 50f, 360f, 9))
    }

    // ---- cue stage ----

    private fun cue(id: String, score: Float) = Cue(id, "text $id", "heading $id", "c_20261001-100000", 0, score)

    @Test
    fun firstCueShowsAtOnce() {
        val v = CueView.next(CueView.EMPTY, listOf(cue("a", 0.5f), cue("b", 0.4f), cue("c", 0.3f), cue("d", 0.2f)), now = 1_000)
        assertEquals("a", v.main?.factId)
        assertEquals(1_000, v.since)
        assertEquals(listOf("b", "c"), v.also.map { it.factId })
    }

    @Test
    fun shownCueDwellsEvenWhenOvertaken() {
        val a = CueView.next(CueView.EMPTY, listOf(cue("a", 0.5f), cue("b", 0.4f)), now = 0)
        val b = CueView.next(a, listOf(cue("b", 0.9f), cue("a", 0.5f)), now = 3_000)
        assertEquals("a", b.main?.factId)
        assertEquals(listOf("b"), b.also.map { it.factId })
    }

    @Test
    fun overtakenAfterTheDwellByAClearMargin() {
        val a = CueView.next(CueView.EMPTY, listOf(cue("a", 0.5f)), now = 0)
        assertEquals("a", CueView.next(a, listOf(cue("b", 0.52f), cue("a", 0.5f)), now = 9_000).main?.factId) // within the margin
        val c = CueView.next(a, listOf(cue("b", 0.6f), cue("a", 0.5f)), now = 9_000)
        assertEquals("b", c.main?.factId)
        assertEquals(9_000, c.since)
    }

    @Test
    fun aCueThatDropsOutIsReplacedAtOnce() {
        val a = CueView.next(CueView.EMPTY, listOf(cue("a", 0.5f)), now = 0)
        val b = CueView.next(a, listOf(cue("b", 0.3f)), now = 100)
        assertEquals("b", b.main?.factId)
        assertEquals(CueView.EMPTY, CueView.next(b, emptyList(), now = 200))
    }

    @Test
    fun scoreUpdatesKeepTheDwellClock() {
        val a = CueView.next(CueView.EMPTY, listOf(cue("a", 0.5f)), now = 0)
        val b = CueView.next(a, listOf(cue("a", 0.7f)), now = 4_000)
        assertEquals(0, b.since)
        assertEquals(0.7f, b.main!!.score, 0f)
    }

    // ---- small helpers ----

    @Test
    fun sessionDatesComeFromTheId() {
        assertEquals("2026-10-12", sessionDate("george_20261012-140301"))
        assertNull(sessionDate("nounderscore"))
        assertNull(sessionDate("x_2026"))
        assertNull(sessionDate("x_abcdefgh-1"))
    }

    @Test
    fun namesSplitIntoFirstAndRest() {
        assertEquals("George" to "M.", splitName(" George   M. "))
        assertEquals("Prince" to null, splitName("Prince"))
        assertEquals("Ana" to "de la Cruz", splitName("Ana de la Cruz"))
    }

    @Test
    fun longNamesShrinkToAFloor() {
        assertEquals(1f, heroShrink(0), 0f)
        assertEquals(1f, heroShrink(10), 0f)
        assertEquals(10f / 12, heroShrink(12), 1e-6f)
        assertEquals(0.6f, heroShrink(17), 1e-6f)
        assertEquals(0.6f, heroShrink(40), 0f)
    }

    @Test
    fun greetingFollowsTheClock() {
        assertEquals("Good morning", greeting(5))
        assertEquals("Good afternoon", greeting(12))
        assertEquals("Good evening", greeting(18))
        assertEquals("Good evening", greeting(2))
    }
}
