package com.therapylocal.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

class IdsTest {
    private lateinit var savedZone: TimeZone

    @Before
    fun pinUtc() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(savedZone)

    @Test
    fun clientIdIsLowercaseAlphanumericOnly() {
        assertEquals("sarahchen", Ids.clientId("Sarah Chen"))
        assertEquals("marcusvancephd", Ids.clientId("Marcus Vance, PhD"))
        assertEquals("alexexample", Ids.clientId("ALEX EXAMPLE"))
        assertEquals("abc123", Ids.clientId("a-b_c 1.2/3"))
        assertEquals("o", Ids.clientId("O'"))
    }

    @Test
    fun clientIdFallsBackWhenNothingUsable() {
        assertEquals("client", Ids.clientId(""))
        assertEquals("client", Ids.clientId("   "))
        assertEquals("client", Ids.clientId("...!"))
        assertEquals("client", Ids.clientId("😀"))
        assertEquals("client", Ids.clientId("山田"))
    }

    @Test
    fun clientIdOutputAlwaysMatchesIdRule() {
        val rnd = Random(7)
        val alphabet = "abcXYZ019 ,.-_'éüß中😀\t"
        repeat(300) {
            val name = String(CharArray(rnd.nextInt(0, 20)) { alphabet[rnd.nextInt(alphabet.length)] })
            val id = Ids.clientId(name)
            assertTrue("'$id' from '$name'", id.matches(Regex("[a-z0-9]+")))
        }
    }

    @Test
    fun sessionIdFormatAndClientPrefix() {
        // 1771934400 s = 20508 days + 12:00:00 after 1970-01-01 = 2026-02-24 12:00:00 UTC
        val id = Ids.sessionId("sarahchen", 1771934400000L)
        assertEquals("sarahchen_20260224-120000", id)
        assertEquals("sarahchen", id.substringBefore('_'))
        // 24-hour clock: 12:00 + 3h 4m 5s = 15:04:05 (not 03:04:05)
        assertEquals("sarahchen_20260224-150405", Ids.sessionId("sarahchen", 1771934400000L + (3 * 3600 + 4 * 60 + 5) * 1000L))
    }

    @Test
    fun sessionIdsSortChronologicallyAndDifferPerSecond() {
        val base = 1771934400000L
        assertEquals("c_20260224-120000", Ids.sessionId("c", base))
        assertEquals("c_20260224-120001", Ids.sessionId("c", base + 1000))
        assertEquals("c_20260224-120000", Ids.sessionId("c", base + 999))
        assertEquals("d_20260224-120000", Ids.sessionId("d", base))
        assertTrue(Ids.sessionId("c", base) < Ids.sessionId("c", base + 1000))
    }
}
