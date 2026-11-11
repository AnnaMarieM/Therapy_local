package com.therapylocal.app.models

import android.content.Context
import android.os.Build
import com.therapylocal.app.config.EmbeddingConfig
import java.io.File

/**
 * What the Qualcomm NPU needs (AD-09). LiteRT-LM reaches it through `libLiteRtDispatch_Qualcomm.so` and the QNN/HTP libraries of ONE QAIRT version,
 * all in the app's native library dir (APK: `litert-npu-runtime-qualcomm` from Google, `qnn-runtime` from Qualcomm), and a model file built for
 * the device's SoC ([ModelRegistry.BACKEND_BUILDS]). A device with an entry in that catalogue is an optimised device; every other device gets a
 * logged reason and uses the GPU, then the CPU. There is no other name-based rule.
 */
object NpuRuntime {
    const val DISPATCH_LIB = "libLiteRtDispatch_Qualcomm.so"
    const val QNN_HTP_LIB = "libQnnHtp.so"
    const val QNN_SYSTEM_LIB = "libQnnSystem.so"
    val REQUIRED_LIBS = listOf(DISPATCH_LIB, QNN_HTP_LIB, QNN_SYSTEM_LIB)

    /** The SoC of this phone as the system names it (`ro.soc.model`), what the catalogue and a model bundle's metadata are compared with. */
    fun deviceSoc(): String = Build.SOC_MODEL

    fun runtimeReason(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir
        val missing = REQUIRED_LIBS.filterNot { File(dir, it).exists() }
        return if (missing.isEmpty()) null else "NPU runtime library missing (${missing.joinToString()})"
    }

    fun available(context: Context): Boolean = runtimeReason(context) == null

    /** Why the NPU cannot run [model] on this device: no validated NPU build for its SoC, or the file is not on the phone yet. Null when it can. */
    fun modelReason(context: Context, model: String, soc: String = deviceSoc()): String? {
        if (model.substringBefore('+') == EmbeddingConfig.MODEL_ID && experimentFile(context) != null) return null
        val build = ModelRegistry.backendBuild(model, "NPU", soc)
            ?: return "no validated NPU model for $soc (${model.substringBefore('+')}; validated on ${ModelRegistry.BACKEND_BUILDS.filter { it.modelId == model.substringBefore('+') && it.accelerator == "NPU" }.mapNotNull { it.soc }.ifEmpty { listOf("no device") }.joinToString()})"
        return if (build.spec.isReady(context)) null else "NPU model file for $soc not downloaded (${build.spec.files.first().name})"
    }

    /**
     * QA build only, for NPU experiments (AD-09): `files/models/npu/embed_override.txt` holds the name of another bundle in that folder to load on the NPU in
     * place of the catalogue's for the embedder. It is compared with the same CPU golden vectors, so only a bundle of the same model can pass the proof.
     */
    fun experimentFile(context: Context): File? {
        if (com.therapylocal.app.BuildConfig.BUILD_TYPE != "qa") return null
        val dir = File(context.getExternalFilesDir(null), "models/npu")
        val name = File(dir, "embed_override.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        return name.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }
    }

    /** The NPU file for [model] on this device, or null. */
    fun modelFile(context: Context, model: String, soc: String = deviceSoc()): File? =
        ModelRegistry.backendBuild(model, "NPU", soc)?.spec?.let { it.file(context, it.files.first()) }?.takeIf { it.exists() }
}
