package com.therapylocal.app.models

import android.content.Context
import java.io.File

data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/**
 * A downloadable model: one or more files stored under the app's external files dir, which survives `adb install -r`
 * updates. [subdir] is null for the Gemma builds only, to keep the path the first version already downloaded to.
 */
data class ModelSpec(val id: String, val title: String, val subdir: String?, val files: List<ModelFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun dir(context: Context): File {
        val base = root(context)
        return (if (subdir == null) base else File(base, subdir)).apply { mkdirs() }
    }

    fun file(context: Context, f: ModelFile): File = File(dir(context), f.name)
    fun part(context: Context, f: ModelFile): File = File(dir(context), f.name + ".part")
    /** Bytes still to fetch: whole files that are missing minus what their .part files already hold. */
    fun missingBytes(context: Context): Long = files.sumOf { f ->
        val part = part(context, f)
        if (isComplete(context, f)) 0L else f.size - (if (part.exists()) minOf(part.length(), f.size) else 0L)
    }

    fun isComplete(context: Context, f: ModelFile): Boolean = file(context, f).let { it.exists() && it.length() == f.size }
    fun isReady(context: Context): Boolean = files.all { file(context, it).let { x -> x.exists() && x.length() == it.size } }
    fun path(context: Context, name: String): String = File(dir(context), name).path

    companion object {
        /** Where every model is stored. */
        fun root(context: Context): File = File(context.getExternalFilesDir(null), "models")
    }
}

/**
 * A build of a model made for one accelerator (AD-09): a different file from the default CPU/GPU one, used only when that accelerator runs the
 * model. This is how a role (the memory LLM, the embedder) keeps ONE model while an accelerator gets its own file of it: the NPU file compiled
 * for a Snapdragon generation, or a GPU-specific file. The entries with an [soc] are the "optimised devices": the chip families the
 * accelerator was validated on, on hardware we own. A device without an entry gets a logged reason and the next backend.
 *
 * @property modelId the model it replaces on [accelerator] ([ModelSpec.id] of the default build)
 * @property accelerator [Accelerator.id] it is for ("NPU", "GPU")
 * @property soc `Build.SOC_MODEL` of the devices it is for (and its bundle metadata must say the same), or null for every device
 * @property spec the download: its own directory, fetched only on a device that has an entry
 * @property evidence where the on-device validation is recorded (test date, device, tokens/s, quality check)
 */
data class BackendBuild(val modelId: String, val accelerator: String, val soc: String?, val spec: ModelSpec, val evidence: String)

object ModelRegistry {
    private const val HF = "https://huggingface.co"

    val GEMMA_E2B = gemma(
        "gemma-4-e2b", "Gemma 4 E2B", "gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", 2_588_147_712L,
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    )

    val GEMMA_E4B = gemma(
        "gemma-4-e4b", "Gemma 4 E4B", "gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm", 3_659_530_240L,
        "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
    )

    fun gemma(variant: GemmaVariant): ModelSpec = when (variant) {
        GemmaVariant.E2B -> GEMMA_E2B
        GemmaVariant.E4B -> GEMMA_E4B
    }

    private fun gemma(id: String, title: String, repo: String, file: String, bytes: Long, sha: String) =
        ModelSpec(id, title, subdir = null, files = listOf(ModelFile(file, "$HF/litert-community/$repo/resolve/main/$file", bytes, sha)))

    /**
     * EmbeddingGemma 2 740M, natively multimodal: text and audio map into one 768-d space.
     * Source: https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm (Apache-2.0, CPU/GPU file).
     */
    const val EMBED_FILE = "embeddinggemma-2-740m.litertlm"

    val EMBED = ModelSpec(
        id = "embeddinggemma-2-740m",
        title = "EmbeddingGemma 2 740M (text + audio)",
        subdir = "embeddinggemma-2-740m",
        files = listOf(
            ModelFile(
                EMBED_FILE, "$HF/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$EMBED_FILE", 484_622_336L,
                "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
            ),
        ),
    )

    /**
     * NVIDIA Canary-1B-v2, int8, on sherpa-onnx: 25 European languages including Czech and English; the only speech recogniser
     * (docs/AD/AD-02.md). The weights are CC-BY-4.0 (NVIDIA); this int8 conversion is a community upload, so the file checksums below pin exactly
     * what was benchmarked (qa/asr_bench).
     * Source: https://huggingface.co/fussraider/canary-1b-v2-sherpa-onnx-int8
     */
    val CANARY_1B_V2 = run {
        val repo = "$HF/fussraider/canary-1b-v2-sherpa-onnx-int8/resolve/main"
        ModelSpec(
            id = "canary-1b-v2", title = "Canary 1B v2 (int8, CPU)", subdir = "canary-1b-v2",
            files = listOf(
                ModelFile("encoder.int8.onnx", "$repo/encoder.int8.onnx", 857_603_131L, "0653fdb7fa13a39f5b63016b91c5319cfdf02736f627aebb57b51aec7c63968e"),
                ModelFile("decoder.int8.onnx", "$repo/decoder.int8.onnx", 557_033_012L, "e77ad881ee06c0e9f63e9fce0173c446b1e9fae2c7e6ac932d78e8547ad9b6a0"),
                ModelFile("tokens.txt", "$repo/tokens.txt", 208_022L, "2c9efe6104fd29522ea27ce0e3aef5d37c690af4e5a4232e643e23ca403ffea3"),
            ),
        )
    }

    /**
     * Speaker diarization (docs/AD/AD-06.md): pyannote segmentation-3.0 finds who speaks when, a speaker-embedding model tells the voices apart.
     * Both run on sherpa-onnx (`OfflineSpeakerDiarization`). The embedding model is `wespeaker_en_voxceleb_resnet34_LM`, the one pyannote's own
     * 3.1 pipeline pairs with segmentation-3.0, and VoxCeleb covers speakers of many nationalities and languages, so it is the least tied to one
     * language of the sherpa-onnx speaker models (the 3D-Speaker ones are Chinese-trained). Voice identity, not words, so Czech is not a special case.
     * Sources: https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0 (converted from pyannote/segmentation-3.0, MIT) and
     * https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models (WeSpeaker, Apache-2.0). Checksums are of the files as downloaded
     * (the embedding one also matches the release's checksum.txt).
     */
    const val DIAR_SEGMENTATION_FILE = "model.onnx"
    const val DIAR_EMBEDDING_FILE = "wespeaker_en_voxceleb_resnet34_LM.onnx"

    val DIARIZATION = ModelSpec(
        id = "diarization-pyannote3-wespeaker", title = "Speaker diarization (pyannote 3.0 + WeSpeaker ResNet34)", subdir = "diarization-pyannote3-wespeaker",
        files = listOf(
            ModelFile(
                DIAR_SEGMENTATION_FILE, "$HF/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx", 5_992_913L,
                "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
            ),
            ModelFile(
                DIAR_EMBEDDING_FILE,
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$DIAR_EMBEDDING_FILE", 26_530_550L,
                "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012",
            ),
        ),
    )

    /**
     * Whisper large-v3, ggml q8_0, for the Quality workflow (docs/AD/AD-22.md), run by whisper.cpp on the CPU in `:batch` (`libwhisper_jni.so`).
     * Upstream (ggerganov/whisper.cpp) publishes large-v3 only as f16 and q5_0; this q8_0 was made with its `whisper-quantize` from the f16 file
     * (3,095,033,483 bytes, sha256 64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2), see research/asr-llm-combo/device/README.md
     * "Round 2". Size and checksum pin exactly what was measured. **There is no download address yet**: nobody hosts this file, so [WHISPER_Q8_URL]
     * is empty and the file is put on a device by hand (`adb push` into the models folder) until it is hosted.
     */
    const val WHISPER_Q8_FILE = "ggml-large-v3-q8_0.bin"
    const val WHISPER_Q8_URL = ""

    val WHISPER_LARGE_V3_Q8 = ModelSpec(
        id = "whisper-large-v3-q8_0", title = "Whisper large-v3 (q8_0, CPU)", subdir = "whisper-large-v3-q8_0",
        files = listOf(ModelFile(WHISPER_Q8_FILE, WHISPER_Q8_URL, 1_656_538_283L, "24bc434f372355688ab9a623077a63e5361a1c41f4d8d648977e39f9b060f09e")),
    )

    /** The speech model a workflow needs: Canary for the control, Whisper large-v3 for Quality, none for Fast (Gemma hears the audio, AD-22). */
    fun speechFor(workflow: com.therapylocal.app.config.Workflow): List<ModelSpec> = when (workflow) {
        com.therapylocal.app.config.Workflow.Canary -> listOf(CANARY_1B_V2)
        com.therapylocal.app.config.Workflow.Fast -> emptyList()
        com.therapylocal.app.config.Workflow.Quality, com.therapylocal.app.config.Workflow.QualityCs -> listOf(WHISPER_LARGE_V3_Q8)
    }

    val ALL = listOf(GEMMA_E2B, GEMMA_E4B, EMBED, CANARY_1B_V2, DIARIZATION, WHISPER_LARGE_V3_Q8)
    fun byId(id: String): ModelSpec = ALL.first { it.id == id }
    fun find(id: String): ModelSpec? = ALL.firstOrNull { it.id == id.substringBefore('+') }

    /** The accelerator-specific builds (AD-09, docs/AD/AD-09.md "Adding an optimised device"). One entry per validated accelerator, SoC and model. */
    val BACKEND_BUILDS: List<BackendBuild> = listOf(
        // EmbeddingGemma 2 740M has an official SM8750 file (litert-community/embeddinggemma-2-740m-litert-lm, embeddinggemma-2-740m_Qualcomm_SM8750.litertlm,
        // 543,522,816 bytes, sha256 ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996) but it does NOT load with litertlm-android 0.18.1 on the
        // SM8750 phone (2026-11-07): "Failed to invoke the compiled model", AD-09 Verification. No entry until a runtime that loads it has been measured.
        BackendBuild(
            modelId = GEMMA_E2B.id, accelerator = "NPU", soc = "SM8750",
            spec = ModelSpec(
                id = "${GEMMA_E2B.id}-npu-sm8750", title = "Gemma 4 E2B (Snapdragon 8 Elite NPU)", subdir = "npu",
                files = listOf(
                    ModelFile(
                        "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
                        "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/fc978c656bfaecebc54c31ccb9a2db7750ef6920/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
                        2_724_793_461L, "248e732911222c173d6c7d7086258990fc009f23a6144fb8abcf43dd111c7a1b",
                    ),
                ),
            ),
            evidence = "docs/AD/AD-09.md#verification (2026-11-07, POCO F8 Pro SM8750: backend NPU, 2 runs, compared with CPU)",
        ),
    )

    /** The build of [modelId] made for [accelerator] on a device with SoC [soc], or null: the default file is used (or, for the NPU, there is no model for it). */
    fun backendBuild(modelId: String, accelerator: String, soc: String): BackendBuild? = BACKEND_BUILDS.firstOrNull {
        it.modelId == modelId.substringBefore('+') && it.accelerator.equals(accelerator, ignoreCase = true) && (it.soc == null || it.soc.equals(soc, ignoreCase = true))
    }

    /**
     * The file [accelerator] loads for [spec] on this device: its own build when the catalogue has one and it is on the phone, else the default
     * file. (Whether a missing build should stop the backend is the accelerator's gate, as for the NPU: see NpuRuntime.modelReason.)
     */
    fun fileFor(context: Context, spec: ModelSpec, accelerator: String, soc: String = android.os.Build.SOC_MODEL): File =
        backendBuild(spec.id, accelerator, soc)?.spec?.let { b -> b.file(context, b.files.first()).takeIf { it.exists() } } ?: spec.file(context, spec.files.first())

    /** The extra files this device fetches, for the models in use: nothing on a device without an entry. */
    fun backendSpecsFor(modelIds: Collection<String>, soc: String): List<ModelSpec> =
        BACKEND_BUILDS.filter { b -> modelIds.any { it == b.modelId } && (b.soc == null || b.soc.equals(soc, ignoreCase = true)) }.map { it.spec }.distinct()
}
