package com.therapylocal.app.vault

import java.io.File
import java.io.IOException

/**
 * Encrypted file: ASCII "TLV1" + [VaultCrypto.seal] (AES-256-GCM) with the file name as AAD, so a file copied to another name does not
 * decrypt. Writes are atomic (sibling .tmp, then rename). Pure JVM.
 */
object SecureFile {
    private val MAGIC = "TLV1".toByteArray(Charsets.US_ASCII)

    fun write(file: File, bytes: ByteArray, key: ByteArray) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + VaultCrypto.seal(key, bytes, aad(file)))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
        }
    }

    /** Null if the file is missing; throws (GeneralSecurityException / IOException) if it cannot be decrypted. */
    fun read(file: File, key: ByteArray): ByteArray? {
        if (!file.exists()) return null
        val raw = file.readBytes()
        if (raw.size < MAGIC.size || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("not a secure file: ${file.name}")
        return VaultCrypto.open(key, raw.copyOfRange(MAGIC.size, raw.size), aad(file))
    }

    fun writeText(file: File, text: String, key: ByteArray) = write(file, text.toByteArray(Charsets.UTF_8), key)
    fun readText(file: File, key: ByteArray): String? = read(file, key)?.toString(Charsets.UTF_8)

    private fun aad(file: File) = file.name.toByteArray(Charsets.UTF_8)
}
