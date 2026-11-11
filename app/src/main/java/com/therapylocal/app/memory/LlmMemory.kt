package com.therapylocal.app.memory

import android.app.ActivityManager
import android.content.Context

/**
 * What the system reports free, for the log lines only. It decides nothing: a GPU/NPU load is never refused for lack of free memory
 * (AD-08); the engine process takes the risk and [com.therapylocal.app.models.BackendGuard] learns from what happens.
 */
object LlmMemory {
    /** Available bytes and the system's low-memory flag. If the system will not say, it reads as 0 and low: the log line shows it, nothing is skipped. */
    fun available(context: Context): Pair<Long, Boolean> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L to true
        val info = ActivityManager.MemoryInfo()
        return runCatching { am.getMemoryInfo(info); info.availMem to info.lowMemory }.getOrDefault(0L to true)
    }
}
