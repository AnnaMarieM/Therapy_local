package com.therapylocal.app.ui

import com.therapylocal.app.ui.data.ClientInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClientInputTest {
    @Test
    fun slugLowercasesAndStripsAccents() {
        assertEquals("georgem", ClientInput.slug("George M."))
        assertEquals("jorgmuller", ClientInput.slug("  Jörg   Müller "))
        assertEquals("", ClientInput.slug("...!"))
    }

    @Test
    fun uniqueIdAddsSuffixOnCollision() {
        assertEquals("marta", ClientInput.uniqueId("marta", setOf("george")))
        assertEquals("marta3", ClientInput.uniqueId("marta", setOf("marta", "marta2")))
    }

    @Test
    fun validateChecksName() {
        assertNull(ClientInput.validate("Marta"))
        assertNotNull(ClientInput.validate("  "))
    }
}
