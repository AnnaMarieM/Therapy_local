package com.therapylocal.app.models

import android.content.Context
import java.io.File

data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/**
 * A downloadable model: one or more files stored under the app's external files dir, which survives `adb install -r`
 * updates. [subdir] is null for Gemma only, to keep the path the first version already downloaded to.
 */
data class ModelSpec(val id: String, val title: String, val subdir: String?, val files: List<ModelFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun dir(context: Context): File {
        val base = File(context.getExternalFilesDir(null), "models")
        return (if (subdir == null) base else File(base, subdir)).apply { mkdirs() }
    }

    fun file(context: Context, f: ModelFile): File = File(dir(context), f.name)
    fun part(context: Context, f: ModelFile): File = File(dir(context), f.name + ".part")
    fun isReady(context: Context): Boolean = files.all { file(context, it).let { x -> x.exists() && x.length() == it.size } }
    fun path(context: Context, name: String): String = File(dir(context), name).path
}

object ModelRegistry {
    private const val HF = "https://huggingface.co"

    val GEMMA = ModelSpec(
        id = "gemma-4-e2b",
        title = "Gemma 4 E2B",
        subdir = null,
        files = listOf(
            ModelFile(
                "gemma-4-E2B-it.litertlm",
                "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                2_588_147_712L,
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            ),
        ),
    )

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

    private const val TOKENS_SHA = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
    private const val TOKENS_SIZE = 816_730L

    private fun whisper(size: String, encBytes: Long, encSha: String, decBytes: Long, decSha: String): ModelSpec {
        val repo = "$HF/csukuangfj/sherpa-onnx-whisper-$size/resolve/main"
        return ModelSpec(
            id = "whisper-$size",
            title = "Whisper $size (int8, CPU)",
            subdir = "whisper-$size",
            files = listOf(
                ModelFile("$size-encoder.int8.onnx", "$repo/$size-encoder.int8.onnx", encBytes, encSha),
                ModelFile("$size-decoder.int8.onnx", "$repo/$size-decoder.int8.onnx", decBytes, decSha),
                ModelFile("$size-tokens.txt", "$repo/$size-tokens.txt", TOKENS_SIZE, TOKENS_SHA),
            ),
        )
    }

    val WHISPER_TINY = whisper(
        "tiny", 12_937_772L, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
        89_855_401L, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
    )
    val WHISPER_BASE = whisper(
        "base", 29_120_534L, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
        130_672_026L, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
    )
    val WHISPER_SMALL = whisper(
        "small", 112_442_483L, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
        262_226_114L, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
    )

    val ALL = listOf(GEMMA, EMBED, WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL)
    fun byId(id: String): ModelSpec = ALL.first { it.id == id }
    fun whisperFor(size: String): ModelSpec = when (size) {
        "tiny" -> WHISPER_TINY
        "small" -> WHISPER_SMALL
        else -> WHISPER_BASE
    }
}
