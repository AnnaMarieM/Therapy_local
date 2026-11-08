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

    val ALL = listOf(GEMMA_E2B, GEMMA_E4B, EMBED, CANARY_1B_V2, DIARIZATION)
    fun byId(id: String): ModelSpec = ALL.first { it.id == id }
}
