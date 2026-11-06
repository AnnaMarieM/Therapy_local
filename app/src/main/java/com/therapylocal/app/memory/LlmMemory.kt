package com.therapylocal.app.memory

import android.app.ActivityManager
import android.content.Context
import com.therapylocal.app.config.MemoryConfig

/** Whether a backend that takes memory from the system (GPU, NPU) fits now, so the low-memory killer is not asked to pick the app. */
object LlmMemory {
    /** Bytes the system needs free to give [modelBytes] to the GPU or NPU and still leave headroom for the app and the rest of the phone. */
    fun gpuNeeds(modelBytes: Long): Long = (modelBytes * MemoryConfig.GPU_MEMORY_FACTOR).toLong() + MemoryConfig.GPU_HEADROOM_BYTES

    /** False if the system reports low memory or has less than [gpuNeeds] available. */
    fun gpuFits(modelBytes: Long, availableBytes: Long, lowMemory: Boolean): Boolean = !lowMemory && availableBytes >= gpuNeeds(modelBytes)

    /** Available bytes and the system's low-memory flag. If the system will not say, it counts as low memory: GPU and NPU are skipped, the CPU runs. */
    fun available(context: Context): Pair<Long, Boolean> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L to true
        val info = ActivityManager.MemoryInfo()
        return runCatching { am.getMemoryInfo(info); info.availMem to info.lowMemory }.getOrDefault(0L to true)
    }
}
