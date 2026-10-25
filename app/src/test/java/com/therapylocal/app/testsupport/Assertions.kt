package com.therapylocal.app.testsupport

import org.json.JSONException
import org.junit.Assert.fail
import java.io.IOException
import java.security.GeneralSecurityException

/** The block must throw one of [allowed] (or a subclass); anything else, including success, fails the test. */
fun assertThrowsOneOf(msg: String, vararg allowed: Class<out Throwable>, block: () -> Any?) {
    try {
        block()
    } catch (t: Throwable) {
        if (allowed.any { it.isInstance(t) }) return
        throw AssertionError("$msg: expected ${allowed.joinToString { it.simpleName }} but got $t", t)
    }
    fail("expected ${allowed.joinToString { it.simpleName }}: $msg")
}

/** Crypto rejection: only GeneralSecurityException is acceptable. */
fun assertRejected(msg: String, block: () -> Any?) = assertThrowsOneOf(msg, GeneralSecurityException::class.java, block = block)

/** Encrypted-file rejection: GeneralSecurityException (bad key/tag) or IOException (not a secure file). */
fun assertRejectedOrIo(msg: String, block: () -> Any?) =
    assertThrowsOneOf(msg, GeneralSecurityException::class.java, IOException::class.java, block = block)

/** Malformed binary input: only IOException is acceptable (never OOM, NegativeArraySize, IndexOutOfBounds). */
fun assertIoException(msg: String, block: () -> Any?) = assertThrowsOneOf(msg, IOException::class.java, block = block)

/** Malformed JSON key file: JSONException (structure) or IllegalArgumentException (bad base64 / rejected value). */
fun assertFailsCleanly(msg: String, block: () -> Any?) =
    assertThrowsOneOf(msg, JSONException::class.java, IllegalArgumentException::class.java, block = block)
