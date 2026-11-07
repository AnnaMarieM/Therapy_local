package com.therapylocal.app.memory

import android.app.ActivityManager
import android.content.Context
import com.therapylocal.app.config.MemoryConfig

/** Whether a backend that takes memory from the system (GPU, NPU) fits now, so the low-memory killer is not asked to pick the app. */
object LlmMemory {
    /** Bytes the system needs free to give [modelBytes] to the GPU or NPU and still leave headroom for the app and the rest of the phone. */
    fun gpuNeeds(modelBytes: Long, factor: Double = MemoryConfig.GPU_MEMORY_FACTOR): Long = (modelBytes * factor).toLong() + MemoryConfig.GPU_HEADROOM_BYTES

    /** The same rule for a cost that is not a multiple of the file (the embedder's depends on its input size, see [MemoryConfig.embedGpuCostBytes]). */
    fun gpuNeedsForCost(costBytes: Long): Long = costBytes + MemoryConfig.GPU_HEADROOM_BYTES

    /** False if the system reports low memory or has less than [gpuNeeds] available. */
    fun gpuFits(modelBytes: Long, availableBytes: Long, lowMemory: Boolean, factor: Double = MemoryConfig.GPU_MEMORY_FACTOR): Boolean = !lowMemory && availableBytes >= gpuNeeds(modelBytes, factor)

    /**
     * The one memory rule for every GPU/NPU load (LLM and embedder alike): why it is not tried now, or null. It reads only the model's own
     * size and what the system reports free at this moment, never the device's name or total RAM.
     */
    fun gate(context: Context, modelBytes: Long, factor: Double = MemoryConfig.GPU_MEMORY_FACTOR): String? {
        val (available, low) = available(context)
        return gateReason(modelBytes, available, low, factor)
    }

    /** [gate] for a GPU/NPU load that takes [costBytes] from the system. */
    fun gateForCost(context: Context, costBytes: Long): String? {
        val (available, low) = available(context)
        return gateReasonForCost(costBytes, available, low)
    }

    fun gateReasonForCost(costBytes: Long, availableBytes: Long, lowMemory: Boolean): String? =
        if (!lowMemory && availableBytes >= gpuNeedsForCost(costBytes)) null
        else "not enough free memory (${availableBytes shr 20} MB free, ${gpuNeedsForCost(costBytes) shr 20} MB needed${if (lowMemory) ", system reports low memory" else ""})"

    /** [gate] on plain numbers, so the JVM can test it. */
    fun gateReason(modelBytes: Long, availableBytes: Long, lowMemory: Boolean, factor: Double = MemoryConfig.GPU_MEMORY_FACTOR): String? =
        if (gpuFits(modelBytes, availableBytes, lowMemory, factor)) null
        else "not enough free memory (${availableBytes shr 20} MB free, ${gpuNeeds(modelBytes, factor) shr 20} MB needed${if (lowMemory) ", system reports low memory" else ""})"

    /** Available bytes and the system's low-memory flag. If the system will not say, it counts as low memory: GPU and NPU are skipped, the CPU runs. */
    fun available(context: Context): Pair<Long, Boolean> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L to true
        val info = ActivityManager.MemoryInfo()
        return runCatching { am.getMemoryInfo(info); info.availMem to info.lowMemory }.getOrDefault(0L to true)
    }
}
