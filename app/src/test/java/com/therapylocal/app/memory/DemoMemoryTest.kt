package com.therapylocal.app.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoMemoryTest {
    private val vec = floatArrayOf(0.5f, -1.25f, 3f)

    private val json = """
        {"version":2,"clients":[{"id":"george","name":"George","age":41,
          "sessions":[{"id":"george_s01","date":"2026-01-14","chunks":[{"idx":1,"text":"hello","vector":"${DemoMemory.encode(vec)}"},{"idx":2,"text":"again"}]}],
          "facts":[{"id":"F1","category":"work","subject":"George","fact":"George is an auditor.","evidence":"I audit","chunk":1,"session":"george_s01","vector":"${DemoMemory.encode(vec)}"},
                   {"id":"F2","fact":"He has a dog.","session":"george_s01"}],
          "supports":[{"fact_id":"F1","session":"george_s02","evidence":"auditing again","chunk":3}]}]}
    """.trimIndent()

    @Test
    fun parsesClientsSessionsFactsAndSupports() {
        val data = DemoMemory.parse(json)
        assertEquals(2, data.version)
        val c = data.clients.single()
        assertEquals("George", c.name)
        assertEquals(41, c.age)
        assertEquals("", c.occupation)
        assertEquals("2026-01-14", c.sessions.single().date)
        assertEquals(listOf(1, 2), c.sessions.single().chunks.map { it.idx })
        assertEquals("F1", c.supports.single().factId)
        assertEquals(3, c.supports.single().chunk)
    }

    @Test
    fun vectorsAreOptionalAndRoundTrip() {
        val c = DemoMemory.parse(json).clients.single()
        assertArrayEquals(vec, c.facts[0].vector, 0f)
        assertArrayEquals(vec, c.sessions[0].chunks[0].vector, 0f)
        assertNull(c.sessions[0].chunks[1].vector)
        assertNull(c.facts[1].vector)
    }

    @Test
    fun missingFactFieldsGetDefaults() {
        val f = DemoMemory.parse(json).clients.single().facts[1].fact
        assertEquals("other", f.category)
        assertEquals("george", f.subject)
        assertEquals("", f.evidence)
        assertEquals(0, f.chunk)
    }

    @Test
    fun clientWithoutOptionalFieldsParses() {
        val c = DemoMemory.parse("""{"clients":[{"id":"marta"}]}""").clients.single()
        assertEquals("Marta", c.name)
        assertNull(c.age)
        assertEquals(0, c.facts.size)
    }
}
