package com.therapylocal.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureFileTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)

    @Test
    fun roundTripCreatesParentDirs() {
        val f = File(tmp.root, "a/b/s1.md")
        SecureFile.write(f, byteArrayOf(1, 2, 3), key)
        assertArrayEquals(byteArrayOf(1, 2, 3), SecureFile.read(f, key))
        SecureFile.writeText(f, "Grüße", key)
        assertEquals("Grüße", SecureFile.readText(f, key))
    }

    @Test
    fun missingFileIsNull() {
        assertNull(SecureFile.read(File(tmp.root, "nope"), key))
        assertNull(SecureFile.readText(File(tmp.root, "nope"), key))
    }

    @Test
    fun fileDoesNotContainPlaintext() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "patient-secret-text", key)
        val raw = f.readBytes()
        assertEquals("TLV1", String(raw, 0, 4, Charsets.US_ASCII))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("patient-secret-text"))
    }

    @Test
    fun wrongKeyThrows() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "x", key)
        assertThrows { SecureFile.read(f, VaultCrypto.randomBytes(32)) }
    }

    @Test
    fun copyToOtherNameFails() {
        val f = File(tmp.root, "s1.md")
        SecureFile.writeText(f, "x", key)
        val copy = File(tmp.root, "s2.md")
        f.copyTo(copy)
        assertThrows { SecureFile.read(copy, key) }
    }

    @Test
    fun wrongMagicIsRejected() {
        val f = File(tmp.root, "plain.md")
        f.writeText("plain text, not encrypted")
        assertThrows { SecureFile.read(f, key) }
    }

    @Test
    fun noTmpLeftBehind() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "one", key)
        SecureFile.writeText(f, "two", key)
        assertEquals(listOf("s.md"), tmp.root.list()!!.toList())
        assertTrue(f.exists())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected exception")
        } catch (_: java.security.GeneralSecurityException) {
        } catch (_: java.io.IOException) {
        }
    }
}
