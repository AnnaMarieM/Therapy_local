package com.therapylocal.app.engine.runner

import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.ModelRegistry

/**
 * Which LLM engines are built with 32-bit activations (docs/AD/AD-19.md). Gemma E2B on the GPU: with the GPU's default precision the output fails the
 * second proof on the Pixel 8 and the Galaxy Tab S7 (issue #48); FLOAT32 passes on all three test devices at no speed cost on the POCO F8 Pro.
 * No device or SoC takes part (the ladder takes no device names into its choices). E4B and every other backend keep the default until measured.
 */
internal object GpuPrecision {
    fun float32(modelId: String, backend: String): Boolean =
        modelId == ModelRegistry.GEMMA_E2B.id && backend.equals(Accelerators.GPU.id, ignoreCase = true)
}
