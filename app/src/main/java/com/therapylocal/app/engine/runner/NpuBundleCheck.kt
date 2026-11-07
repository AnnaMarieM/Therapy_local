package com.therapylocal.app.engine.runner

import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.io.File

/**
 * Reads what an NPU model bundle says about itself (AD-06) before the NPU is asked to run it: the NPU brand and the SoC it was compiled for.
 * A file for another chip fails inside the driver, so it is refused here with the facts instead. Runs in the engine process, which already
 * has LiteRT-LM loaded; the main process never opens a bundle.
 */
internal object NpuBundleCheck {
    private const val TAG = "Npu"

    fun verify(file: File, deviceSoc: String) {
        ModelInfo.from(file.path).use { info ->
            val brand = info.npuBrand(Modality.TEXT).name
            val soc = info.socName(Modality.TEXT)
            AppLog.i(TAG, "npu_bundle", LogCtx.NONE, "file" to file.name, "npu_brand" to brand, "bundle_soc" to soc, "device_soc" to deviceSoc)
            check(brand.contains("QUALCOMM", ignoreCase = true)) { "NPU bundle ${file.name} is for $brand, not Qualcomm" }
            check(soc.equals(deviceSoc, ignoreCase = true)) { "NPU bundle ${file.name} was compiled for '$soc', this device is $deviceSoc" }
        }
    }
}
