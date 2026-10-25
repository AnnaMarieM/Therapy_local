package com.therapylocal.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import com.therapylocal.app.testsupport.assertRejectedOrIo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.random.Random

/**
 * SecureFile contract (docs/SECURITY.md): "TLV1" + AES-256-GCM, AAD = file name, written via a sibling .tmp then rename. NOTE: the write is NOT proven atomic here (SecureFile.write
 * deletes the committed target before retrying a failed rename, so a second failure loses the old data; see the report). Whatever is wrong with the bytes on disk,
 * read() must throw (GeneralSecurityException / IOException), never return different content.
 */
class SecureFileTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val otherKey = ByteArray(32) { (it + 2).toByte() }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int =
        (0..hay.size - needle.size).firstOrNull { i -> needle.indices.all { hay[i + it] == needle[it] } } ?: -1

    @Test
    fun bytesRoundTripAndParentDirsAreCreated() {
        val f = File(tmp.root, "a/b/c/s1.bin")
        val data = Random(1).nextBytes(1000)
        SecureFile.write(f, data, key)
        assertArrayEquals(data, SecureFile.read(f, key))
    }

    @Test
    fun textRoundTripIncludingUnicode() {
        val f = File(tmp.root, "s1.md")
        for (text in listOf("", "plain", "Grüße, 日本語, emoji 😀, line1\nline2\r\n", "\u0000nul")) {
            SecureFile.writeText(f, text, key)
            assertEquals(text, SecureFile.readText(f, key))
        }
    }

    @Test
    fun emptyAndLargeContent() {
        val f = File(tmp.root, "e.bin")
        SecureFile.write(f, ByteArray(0), key)
        assertArrayEquals(ByteArray(0), SecureFile.read(f, key)) // empty is not null (null means missing)
        val big = Random(2).nextBytes(3 shl 20)
        SecureFile.write(f, big, key)
        assertArrayEquals(big, SecureFile.read(f, key))
    }

    @Test
    fun missingFileIsNullButExistingBadFileIsNot() {
        assertNull(SecureFile.read(File(tmp.root, "nope"), key))
        assertNull(SecureFile.readText(File(tmp.root, "nope"), key))
        assertNull(SecureFile.read(File(tmp.root, "no/such/dir/nope"), key))
        // existing files that are not valid secure files must throw, never return null or content
        val garbage = File(tmp.root, "garbage.bin").apply { writeBytes(Random(3).nextBytes(100).also { it[0] = 0 }) }
        assertRejectedOrIo("random garbage") { SecureFile.read(garbage, key) }
        assertRejectedOrIo("random garbage as text") { SecureFile.readText(garbage, key) }
        val plain = File(tmp.root, "plain.md").apply { writeText("plaintext session notes, never encrypted") }
        assertRejectedOrIo("plaintext file") { SecureFile.readText(plain, key) }
        val empty = File(tmp.root, "empty.md").apply { writeBytes(ByteArray(0)) }
        assertRejectedOrIo("existing but empty file") { SecureFile.read(empty, key) }
    }

    @Test
    fun fileDoesNotContainThePlaintextOrItsUtf8() {
        val f = File(tmp.root, "s.md")
        val marker = "PATIENT-SECRET-MARKER-Grüße"
        SecureFile.writeText(f, marker.repeat(20), key)
        val raw = f.readBytes()
        assertEquals("TLV1", String(raw, 0, 4, Charsets.US_ASCII))
        assertEquals(-1, String(raw, Charsets.ISO_8859_1).indexOf("PATIENT-SECRET"))
        assertEquals(-1, String(raw, Charsets.ISO_8859_1).indexOf("SECRET-MARKER"))
        assertEquals(-1, indexOf(raw, "Grüße".toByteArray(Charsets.UTF_8))) // the non-ASCII part, as its UTF-8 bytes
        assertEquals(-1, indexOf(raw, "Gr".toByteArray() + byteArrayOf(0xC3.toByte(), 0xBC.toByte())))
        assertEquals(4 + 12 + marker.toByteArray().size * 20 + 16, raw.size) // magic + IV + ciphertext + tag
    }

    @Test
    fun theSamePlaintextAndKeyGivesDifferentFilesEveryTime() {
        val a = File(tmp.root, "x/s.md")
        val b = File(tmp.root, "y/s.md")
        SecureFile.writeText(a, "same", key)
        SecureFile.writeText(b, "same", key)
        assertFalse(a.readBytes().contentEquals(b.readBytes()))
    }

    @Test
    fun differentKeysGiveDifferentFilesAndDoNotCrossOpen() {
        val f1 = File(tmp.root, "k1/s.md")
        val f2 = File(tmp.root, "k2/s.md")
        SecureFile.writeText(f1, "same", key)
        SecureFile.writeText(f2, "same", otherKey)
        assertFalse(f1.readBytes().contentEquals(f2.readBytes()))
        assertRejectedOrIo("f1 with otherKey") { SecureFile.read(f1, otherKey) }
        assertRejectedOrIo("f2 with key") { SecureFile.read(f2, key) }
        assertEquals("same", SecureFile.readText(f1, key))
        assertEquals("same", SecureFile.readText(f2, otherKey))
    }

    @Test
    fun wrongKeyThrowsEvenForEmptyContent() {
        val f = File(tmp.root, "s.md")
        SecureFile.write(f, ByteArray(0), key)
        assertRejectedOrIo("wrong key") { SecureFile.read(f, otherKey) }
    }

    @Test
    fun copyToOtherNameFailsAndMoveBackWorks() {
        val f = File(tmp.root, "s1.md")
        SecureFile.writeText(f, "x", key)
        val copy = File(tmp.root, "s2.md")
        f.copyTo(copy)
        assertRejectedOrIo("copy under other name") { SecureFile.read(copy, key) }
        // same name in another directory is the same AAD (the AAD is the file name only)
        val sameName = File(tmp.newFolder("other"), "s1.md")
        f.copyTo(sameName)
        assertEquals("x", SecureFile.readText(sameName, key))
    }

    @Test
    fun flippingAnyByteOnDiskIsRejected() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "some session text, 40+ bytes long...", key)
        val good = f.readBytes()
        for (i in good.indices) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor 0x04).toByte()
            f.writeBytes(bad)
            assertRejectedOrIo("byte $i") { SecureFile.read(f, key) }
        }
        f.writeBytes(good)
        assertEquals("some session text, 40+ bytes long...", SecureFile.readText(f, key))
    }

    @Test
    fun truncationAtEveryLengthIsRejected() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "some session text", key)
        val good = f.readBytes()
        for (len in 0 until good.size) {
            f.writeBytes(good.copyOf(len))
            assertRejectedOrIo("truncated to $len") { SecureFile.read(f, key) }
        }
    }

    @Test
    fun appendedBytesAreRejected() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "some session text", key)
        val good = f.readBytes()
        for (extra in listOf(byteArrayOf(0), "TLV1".toByteArray(), ByteArray(16))) {
            f.writeBytes(good + extra)
            assertRejectedOrIo("appended ${extra.size}") { SecureFile.read(f, key) }
        }
    }

    @Test
    fun filesWithoutTheMagicAreRejected() {
        val f = File(tmp.root, "plain.md")
        f.writeText("plain text, not encrypted")
        assertRejectedOrIo("plaintext file") { SecureFile.read(f, key) }
        f.writeBytes(ByteArray(0))
        assertRejectedOrIo("empty file") { SecureFile.read(f, key) }
        f.writeBytes("TLV".toByteArray())
        assertRejectedOrIo("short magic") { SecureFile.read(f, key) }
        f.writeBytes("TLV1".toByteArray())
        assertRejectedOrIo("magic only") { SecureFile.read(f, key) }
        f.writeBytes("TLV2".toByteArray() + ByteArray(60))
        assertRejectedOrIo("wrong version") { SecureFile.read(f, key) }
    }

    @Test
    fun rewriteReturnsTheLastContentAndLeavesNoTempFile() {
        val f = File(tmp.root, "d/s.md")
        SecureFile.writeText(f, "one", key)
        SecureFile.writeText(f, "two", key)
        SecureFile.writeText(f, "three, which is longer", key)
        SecureFile.writeText(f, "4", key) // shorter than before: no stale tail may remain
        assertEquals("4", SecureFile.readText(f, key))
        assertEquals(listOf("s.md"), f.parentFile!!.list()!!.toList())
    }

    @Test
    fun aStaleTempFileFromACrashDoesNotBreakOrLeakIntoTheNextWrite() {
        val f = File(tmp.root, "s.md")
        SecureFile.writeText(f, "committed", key)
        File(tmp.root, "s.md.tmp").writeBytes(ByteArray(500) { 7 }) // a crash left garbage behind
        assertEquals("committed", SecureFile.readText(f, key)) // the real file is untouched by the stale temp
        SecureFile.writeText(f, "next", key)
        assertEquals("next", SecureFile.readText(f, key))
        assertEquals(listOf("s.md"), tmp.root.list()!!.toList())
    }

    @Test
    fun unrelatedSiblingsAreNotTouched() {
        val a = File(tmp.root, "a.md")
        val b = File(tmp.root, "b.md")
        SecureFile.writeText(a, "A", key)
        SecureFile.writeText(b, "B", key)
        SecureFile.writeText(a, "A2", key)
        assertEquals("B", SecureFile.readText(b, key))
        assertEquals(setOf("a.md", "b.md"), tmp.root.list()!!.toSet())
    }

    @Test
    fun failureToReplaceThrowsInsteadOfClaimingSuccess() {
        val target = File(tmp.root, "dir.md")
        File(target, "child").apply { parentFile!!.mkdirs(); writeText("x") } // a non-empty directory cannot be replaced by a file
        assertRejectedOrIo("cannot replace") { SecureFile.write(target, byteArrayOf(1), key) }
        assertTrue(File(target, "child").exists())
    }
}
