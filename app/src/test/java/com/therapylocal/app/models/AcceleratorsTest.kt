package com.therapylocal.app.models

import com.therapylocal.app.config.BackendPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adding a chip is one registration; these pin what that registration buys. */
class AcceleratorsTest {
    @After
    fun cleanUp() {
        Accelerators.all().filter { it.id !in setOf("NPU", "GPU", "CPU") }.forEach { Accelerators.unregister(it.id) }
    }

    @Test
    fun theBuiltInOrderIsNpuGpuCpu() {
        assertEquals(listOf("NPU", "GPU", "CPU"), Accelerators.order("gemma-4-e2b", ModelRuntime.LITERT))
        assertEquals("only the CPU has an ONNX binding by default", listOf("CPU"), Accelerators.order("canary-1b-v2", ModelRuntime.ONNX))
    }

    @Test
    fun aDedicatedChipIsTriedFirstForItsModelOnlyAndTheCpuStaysLast() {
        Accelerators.register(Accelerator(id = "MTK_APU", priority = 50, models = setOf("gemma-4-e2b"), litert = true))
        assertEquals(listOf("MTK_APU", "NPU", "GPU", "CPU"), Accelerators.order("gemma-4-e2b", ModelRuntime.LITERT))
        assertEquals("the audio build of the same model is served too", listOf("MTK_APU", "NPU", "GPU", "CPU"), Accelerators.order("gemma-4-e2b+audio", ModelRuntime.LITERT))
        assertEquals("other models never see it", listOf("NPU", "GPU", "CPU"), Accelerators.order("gemma-4-e4b", ModelRuntime.LITERT))
    }

    @Test
    fun anOnnxExecutionProviderJoinsSpeechRecognitionByRegistration() {
        Accelerators.register(Accelerator(id = "QNN_HTP", priority = 80, onnxProvider = "qnn"))
        assertEquals(listOf("QNN_HTP", "CPU"), Accelerators.order("canary-1b-v2", ModelRuntime.ONNX))
        assertFalse("no LiteRT binding, so LiteRT models skip it", "QNN_HTP" in Accelerators.order("gemma-4-e2b", ModelRuntime.LITERT))
    }

    @Test
    fun aNewChipIsGuardedLikeTheBuiltInsAndSortsInTheSettings() {
        Accelerators.register(Accelerator(id = "EDGE_TPU", priority = 60, litert = true))
        val g = BackendGuard(InMemoryStore(), "b").also { it.recover(null) }
        repeat(BackendGuard.BLOCK_UNPROVEN) { g.attempting("m", "EDGE_TPU"); g.failed("m", "EDGE_TPU", "boom") }
        assertTrue(g.blocked("m", "EDGE_TPU"))
        g.attempting("m", "CPU"); g.proven("m", "CPU")
        g.skipped("m", "GPU", "not enough free memory")
        assertEquals(listOf("EDGE_TPU", "GPU", "CPU"), g.overview().single().backends.keys.toList())
    }

    @Test
    fun forcingAChipStillKeepsTheFallback() {
        Accelerators.register(Accelerator(id = "EDGE_TPU", priority = 60, litert = true))
        assertEquals(listOf("EDGE_TPU", "CPU"), BackendPolicy.order(Accelerators.order("m", ModelRuntime.LITERT), "edge_tpu"))
    }

    @Test
    fun thereIsExactlyOneFallbackAndItCannotBeRemoved() {
        assertThrows(IllegalArgumentException::class.java) { Accelerators.register(Accelerator(id = "CPU2", priority = 1, fallback = true)) }
        assertThrows(IllegalArgumentException::class.java) { Accelerators.unregister("CPU") }
        assertTrue(Accelerators.isFallback("cpu"))
        assertFalse(Accelerators.isFallback("GPU"))
    }

    @Test
    fun idsThatWouldBreakStorageOrLogsAreRejected() {
        listOf("gpu", "", "A@B", "A|B", "A,B", "A;B").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { Accelerator(id = bad, priority = 1) }
        }
    }

    private class InMemoryStore : BackendGuard.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }
}
