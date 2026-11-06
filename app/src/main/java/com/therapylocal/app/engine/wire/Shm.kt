package com.therapylocal.app.engine.wire

import android.os.SharedMemory
import android.system.OsConstants
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The size rules of [Shm], pure so a JVM test can pin them. */
object ShmMath {
    /** `SharedMemory` rejects size 0, so an empty payload still gets a 2-byte region and travels with `n=0` beside it. */
    fun regionBytes(n: Int, elemBytes: Int): Int = maxOf(2, Math.multiplyExact(n, elemBytes))

    /** A string goes inline at up to the limit (UTF-8 bytes) and through shared memory above it. */
    fun goesInline(utf8Bytes: Int): Boolean = utf8Bytes <= EngineConfig.INLINE_MAX_BYTES
}

/**
 * The only `SharedMemory` helper (shorts and bytes). The sender closes its region in a `finally` after the reply; the receiver
 * closes its copy right after reading. Regions are sealed read-only before they are sent.
 */
object Shm {
    private const val TAG = "Shm"

    fun shorts(pcm: ShortArray): SharedMemory {
        val shm = SharedMemory.create("pcm", ShmMath.regionBytes(pcm.size, 2))
        try {
            val map = shm.mapReadWrite()
            try {
                map.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            } finally {
                SharedMemory.unmap(map)
            }
            shm.setProtect(OsConstants.PROT_READ)
        } catch (t: Throwable) {
            shm.close()
            throw t
        }
        AppLog.d(TAG, "shm_write", LogCtx.NONE, "samples" to pcm.size)
        return shm
    }

    fun bytes(b: ByteArray): SharedMemory {
        val shm = SharedMemory.create("bytes", ShmMath.regionBytes(b.size, 1))
        try {
            val map = shm.mapReadWrite()
            try {
                map.put(b)
            } finally {
                SharedMemory.unmap(map)
            }
            shm.setProtect(OsConstants.PROT_READ)
        } catch (t: Throwable) {
            shm.close()
            throw t
        }
        AppLog.d(TAG, "shm_write", LogCtx.NONE, "bytes" to b.size)
        return shm
    }

    /** Reads [n] shorts and closes [shm]. */
    fun readShorts(shm: SharedMemory, n: Int): ShortArray = shm.use {
        val out = ShortArray(n)
        if (n > 0) {
            val map = it.mapReadOnly()
            try {
                map.order(ByteOrder.nativeOrder()).asShortBuffer().get(out)
            } finally {
                SharedMemory.unmap(map)
            }
        }
        AppLog.d(TAG, "shm_read", LogCtx.NONE, "samples" to n)
        out
    }

    /** Reads [n] bytes and closes [shm]. */
    fun readBytes(shm: SharedMemory, n: Int): ByteArray = shm.use {
        val out = ByteArray(n)
        if (n > 0) {
            val map: ByteBuffer = it.mapReadOnly()
            try {
                map.get(out)
            } finally {
                SharedMemory.unmap(map)
            }
        }
        AppLog.d(TAG, "shm_read", LogCtx.NONE, "bytes" to n)
        out
    }
}
