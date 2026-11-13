package com.therapylocal.app.engine.runner

import com.therapylocal.app.models.ModelRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AD-19: FLOAT32 activations for Gemma E2B on the GPU, and nowhere else. */
class GpuPrecisionTest {
    private val e2b = ModelRegistry.GEMMA_E2B.id
    private val e4b = ModelRegistry.GEMMA_E4B.id

    @Test fun e2b_on_the_gpu_uses_float32() = assertTrue(GpuPrecision.float32(e2b, "GPU"))

    @Test fun the_backend_name_is_not_case_sensitive() = assertTrue(GpuPrecision.float32(e2b, "gpu"))

    @Test fun e2b_on_the_cpu_and_the_npu_keeps_the_default() {
        assertFalse(GpuPrecision.float32(e2b, "CPU"))
        assertFalse(GpuPrecision.float32(e2b, "NPU"))
    }

    @Test fun e4b_keeps_the_default_on_every_backend() {
        for (b in listOf("GPU", "CPU", "NPU")) assertFalse("E4B on $b", GpuPrecision.float32(e4b, b))
    }

    @Test fun an_unknown_model_keeps_the_default() = assertFalse(GpuPrecision.float32("some-other-model", "GPU"))
}
