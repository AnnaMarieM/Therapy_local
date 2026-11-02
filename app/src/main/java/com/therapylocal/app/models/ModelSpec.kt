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
        val base = File(context.getExternalFilesDir(null), "models")
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

    private const val TOKENS_SHA = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
    private const val TOKENS_SIZE = 816_730L
    private const val TOKENS_EN_SHA = "d03b260a2292a4f324369d11d4b9f10022605f301849ee3799849725828c2999"
    private const val TOKENS_EN_SIZE = 835_554L

    /** [variant] is the repo and file prefix: "base" is multilingual, "base.en" English only. */
    private fun whisper(size: String, variant: String, encBytes: Long, encSha: String, decBytes: Long, decSha: String, tokensBytes: Long, tokensSha: String): ModelSpec {
        val repo = "$HF/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main"
        return ModelSpec(
            id = "whisper-$variant",
            title = "Whisper $size (int8, CPU)" + if (variant.endsWith(".en")) ", English" else "",
            subdir = "whisper-$variant",
            files = listOf(
                ModelFile("$variant-encoder.int8.onnx", "$repo/$variant-encoder.int8.onnx", encBytes, encSha),
                ModelFile("$variant-decoder.int8.onnx", "$repo/$variant-decoder.int8.onnx", decBytes, decSha),
                ModelFile("$variant-tokens.txt", "$repo/$variant-tokens.txt", tokensBytes, tokensSha),
            ),
        )
    }

    val WHISPER_TINY = whisper(
        "tiny", "tiny", 12_937_772L, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
        89_855_401L, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925", TOKENS_SIZE, TOKENS_SHA,
    )
    val WHISPER_BASE = whisper(
        "base", "base", 29_120_534L, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
        130_672_026L, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", TOKENS_SIZE, TOKENS_SHA,
    )
    val WHISPER_SMALL = whisper(
        "small", "small", 112_442_483L, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
        262_226_114L, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", TOKENS_SIZE, TOKENS_SHA,
    )
    // Bigger multilingual builds, for languages the small ones transcribe badly. They have no English-only build here, English uses them as they are.
    val WHISPER_MEDIUM = whisper(
        "medium", "medium", 374_196_283L, "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e",
        571_059_257L, "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747", TOKENS_SIZE, TOKENS_SHA,
    )
    val WHISPER_TURBO = whisper(
        "turbo", "turbo", 674_716_297L, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
        361_080_764L, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547", TOKENS_SIZE, TOKENS_SHA,
    )
    val WHISPER_LARGE_V3 = whisper(
        "large-v3", "large-v3", 766_671_985L, "d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85",
        1_008_265_203L, "ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af", TOKENS_SIZE, TOKENS_SHA,
    )
    val WHISPER_TINY_EN = whisper(
        "tiny", "tiny.en", 12_937_772L, "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6",
        89_853_865L, "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527", TOKENS_EN_SIZE, TOKENS_EN_SHA,
    )
    val WHISPER_BASE_EN = whisper(
        "base", "base.en", 29_120_534L, "ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823",
        130_669_978L, "f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41", TOKENS_EN_SIZE, TOKENS_EN_SHA,
    )
    val WHISPER_SMALL_EN = whisper(
        "small", "small.en", 112_442_483L, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f",
        262_223_042L, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb", TOKENS_EN_SIZE, TOKENS_EN_SHA,
    )

    /**
     * NVIDIA Parakeet-TDT 0.6B v3, int8, on sherpa-onnx: 25 European languages including Czech (11.0 percent WER on FLEURS, per its model card).
     * Source: https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 (CC-BY-4.0).
     */
    val PARAKEET_V3 = run {
        val repo = "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
        ModelSpec(
            id = "parakeet-tdt-0.6b-v3", title = "Parakeet TDT 0.6B v3 (int8, CPU)", subdir = "parakeet-tdt-0.6b-v3",
            files = listOf(
                ModelFile("encoder.int8.onnx", "$repo/encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                ModelFile("decoder.int8.onnx", "$repo/decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                ModelFile("joiner.int8.onnx", "$repo/joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                ModelFile("tokens.txt", "$repo/tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ),
        )
    }

    /** The Whisper size used until the settings choose another: base is too weak for Czech. */
    const val DEFAULT_WHISPER_SIZE = "small"

    val ALL = listOf(GEMMA_E2B, GEMMA_E4B, EMBED, WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL, WHISPER_TINY_EN, WHISPER_BASE_EN, WHISPER_SMALL_EN, WHISPER_MEDIUM, WHISPER_TURBO, WHISPER_LARGE_V3, PARAKEET_V3)
    fun byId(id: String): ModelSpec = ALL.first { it.id == id }

    /** The Whisper build for a client's language: English has its own English-only build, every other language uses the multilingual one. */
    fun whisperFor(size: String, language: SpeechLanguage): ModelSpec = when (size) {
        "medium" -> WHISPER_MEDIUM
        "turbo" -> WHISPER_TURBO
        "large-v3" -> WHISPER_LARGE_V3
        else -> if (language == SpeechLanguage.English) when (size) {
            "tiny" -> WHISPER_TINY_EN
            "small" -> WHISPER_SMALL_EN
            else -> WHISPER_BASE_EN
        } else when (size) {
            "tiny" -> WHISPER_TINY
            "small" -> WHISPER_SMALL
            else -> WHISPER_BASE
        }
    }
}
