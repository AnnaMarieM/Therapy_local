package com.therapylocal.app.engine

import com.therapylocal.app.models.BackendLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineErrorsTest {
    private class LiteRtLmJniException(m: String?) : RuntimeException(m)

    private fun roundTrip(t: Throwable, redact: Boolean = false) = EngineErrors.rebuild(EngineErrors.capture(t, redact))

    @Test
    fun eachMappedClassKeepsItsClassAndMessage() {
        val all = listOf(
            IllegalStateException("a"), IllegalArgumentException("b"), UnsupportedOperationException("c"), OutOfMemoryError("d"),
            IndexOutOfBoundsException("e"), NullPointerException("f"), RuntimeException("g"), Exception("h"),
        )
        for (t in all) {
            val r = roundTrip(t)
            assertEquals(t.javaClass, r.javaClass)
            assertEquals(t.message, r.message)
        }
    }

    @Test
    fun anUnknownClassKeepsItsSimpleNameAndMessage() {
        val r = roundTrip(LiteRtLmJniException("native"))
        assertTrue(r is RemoteEngineException)
        assertEquals("LiteRtLmJniException", BackendLadder.name(r))
        assertEquals("native", r.message)
    }

    @Test
    fun anAnonymousClassKeepsItsFullName() {
        val t = object : RuntimeException("x") {}
        val r = roundTrip(t)
        assertEquals(t.javaClass.name, BackendLadder.name(r))
        assertTrue(BackendLadder.name(r).isNotEmpty())
    }

    @Test
    fun interruptedDoesNotInterruptTheCaller() {
        val r = roundTrip(InterruptedException("stop"))
        assertTrue(r is IllegalStateException)
        assertEquals("interrupted in host", r.message)
    }

    @Test
    fun causeTypesSurviveAsASyntheticChain() {
        val t = IllegalStateException("outer", LiteRtLmJniException("mid").also { it.initCause(IllegalArgumentException("root")) })
        val r = roundTrip(t)
        val names = generateSequence(r) { it.cause }.map { BackendLadder.name(it) }.toList()
        assertEquals(listOf("IllegalStateException", "LiteRtLmJniException", "IllegalArgumentException"), names.map { it.substringAfterLast('.').substringAfterLast('$') })
    }

    @Test
    fun theExceedsMaximumMessageSurvives() {
        val r = roundTrip(IllegalArgumentException("input exceeds maximum length of 512"))
        assertTrue(r.message!!.contains("exceeds maximum"))
    }

    @Test
    fun aNullMessageStaysNull() {
        assertNull(roundTrip(IllegalStateException()).message)
    }

    @Test
    fun redactionDropsTheMessageButKeepsTypesAndFrames() {
        val e = EngineErrors.capture(IllegalStateException("patient said x", LiteRtLmJniException("y")), redact = true)
        assertNull(e.message)
        assertEquals("IllegalStateException", e.type)
        assertEquals(1, e.causes.size)
        assertTrue(e.frames.isNotEmpty())
    }

    @Test
    fun aHugeMessageIsCutTo2000Characters() {
        val e = EngineErrors.capture(RuntimeException("x".repeat(1_000_000)), redact = false)
        assertEquals(EngineErrors.MAX_MESSAGE, e.message!!.length)
    }

    @Test
    fun framesAreCutTo8KbEvenForDeepStacks() {
        fun deep(n: Int): Throwable = if (n == 0) RuntimeException("x") else deep(n - 1)
        val t = deep(300)
        t.stackTrace = Array(5000) { StackTraceElement("com.example.VeryLongClassNameForTheTest$it", "someLongMethodName", "F.kt", it) }
        val e = EngineErrors.capture(t, redact = false)
        assertTrue(e.frames.toByteArray().size <= EngineErrors.MAX_FRAMES_BYTES)
    }

    @Test
    fun atMostTenCauses() {
        var t: Throwable = RuntimeException("root")
        repeat(50) { t = RuntimeException("c$it", t) }
        val e = EngineErrors.capture(t, redact = false)
        assertEquals(EngineErrors.MAX_CAUSES, e.causes.size)
        assertEquals(EngineErrors.MAX_CAUSES, generateSequence(roundTrip(t)) { it.cause }.count() - 1)
    }

    @Test
    fun aCyclicChainListsEachThrowableOnce() {
        val a = IllegalStateException("a")
        val b = LiteRtLmJniException("b")
        a.initCause(b)
        b.initCause(a)
        val e = EngineErrors.capture(a, redact = false)
        assertEquals(1, e.causes.size)
        assertTrue(e.causes.single().endsWith("LiteRtLmJniException"))
    }

    @Test
    fun aThrowingGetterGivesAPlaceholder() {
        val t = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("boom")
        }
        assertEquals("(message unreadable)", EngineErrors.capture(t, redact = false).message)
    }

    @Test
    fun aMarshalledOutOfMemoryStaysAnOutOfMemoryError() {
        val r = roundTrip(OutOfMemoryError("Failed to allocate"))
        assertTrue(r is OutOfMemoryError)
        assertEquals("Failed to allocate", r.message)
    }
}
