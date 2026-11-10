package com.therapylocal.app.engine.runner

import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.io.File

/**
 * Reads what an NPU model bundle says about itself (AD-09) before the NPU is asked to run it: the NPU brand and the SoC it was compiled for, when it says.
 * A file for another chip fails inside the driver, so it is refused here with the facts instead. Runs in the engine process, which already
 * has LiteRT-LM loaded; the main process never opens a bundle.
 */
internal object NpuBundleCheck {
    private const val TAG = "Npu"

    fun verify(file: File, deviceSoc: String) {
        ModelInfo.from(file.path).use { info ->
            // Which modality carries the NPU metadata depends on the bundle (an embedder file may carry it on none): log all, judge by the first that has it.
            val per = Modality.entries.map { m -> Triple(m, runCatching { info.npuBrand(m).name }.getOrDefault("error"), runCatching { info.socName(m) }.getOrNull()) }
            AppLog.i(TAG, "npu_bundle_modalities", LogCtx.NONE, "file" to file.name, "model_type" to info.modelType.name, "per_modality" to per.joinToString(";") { "${it.first}=${it.second}/${it.third}" })
            val hit = per.firstOrNull { it.second != "UNKNOWN" && it.second != "error" } ?: per.first()
            val brand = hit.second
            val soc = hit.third
            AppLog.i(TAG, "npu_bundle", LogCtx.NONE, "file" to file.name, "npu_brand" to brand, "bundle_soc" to soc, "device_soc" to deviceSoc)
            // A bundle that says nothing (the official EmbeddingGemma file reports UNKNOWN and no SoC on every modality, measured 2026-11-07) is not refused:
            // the catalogue already ties the file to this SoC. One that says something else is refused.
            check(brand == "UNKNOWN" || brand == "error" || brand.contains("QUALCOMM", ignoreCase = true)) { "NPU bundle ${file.name} is for $brand, not Qualcomm" }
            check(soc.isNullOrEmpty() || soc.equals(deviceSoc, ignoreCase = true)) { "NPU bundle ${file.name} was compiled for '$soc', this device is $deviceSoc" }
        }
    }
}
