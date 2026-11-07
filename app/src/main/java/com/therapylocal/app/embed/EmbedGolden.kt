package com.therapylocal.app.embed

import android.content.Context
import com.therapylocal.app.asr.Pcm
import org.json.JSONObject
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The embedder's proof of correct output. Passing "similar > unrelated" is not enough: a GPU that returns wrong numbers (a 16-bit overflow,
 * a bad kernel) can still rank three sentences the right way (see issue #48, where an LLM was "proven" while its output was broken). So the
 * self-test also compares what the engine returns for a few FIXED inputs with the vectors the same model file gives on the CPU, stored in
 * `assets/embed_golden.json` (written by the QA `BackendProbeActivity` with `--ez golden true` on a CPU run) and keyed by the model file's sha256.
 * A vector whose cosine to its golden one is under [MIN_COSINE] fails the proof: the backend gets a strike, the next one is tried.
 *
 * No reference for this exact model file (the asset is missing, or was made for another file) fails the proof of every GPU/NPU and logs an
 * error; the CPU, the reference itself, still loads. `EmbedGoldenAssetTest` fails the build when the asset does not match the model's sha256.
 */
object EmbedGolden {
    const val ASSET = "embed_golden.json"

    /**
     * Fixed text inputs. Together with [tone] these are the only inputs the golden vectors exist for. The cosine of a correct backend to the CPU
     * reference is about 1.0 (the tablet measurement is in docs/MEMORY.md); unrelated sentences score 0.75-0.89 against each other, so a
     * threshold near 0.9 would catch nothing: this one sits where only numerically equivalent output passes.
     */
    const val MIN_COSINE = 0.98f

    const val QUERY = "my boss keeps criticising my work"
    const val RELATED = "my manager is always unhappy with my reports"
    const val UNRELATED = "we went hiking in the mountains last weekend"

    /** One second of a 440 Hz tone, the audio input. */
    fun tone(): ShortArray = ShortArray(Pcm.SAMPLE_RATE) { (8000 * sin(2 * Math.PI * 440 * it / Pcm.SAMPLE_RATE)).toInt().toShort() }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) -1f else (ab / (sqrt(aa) * sqrt(bb))).toFloat()
    }

    /** The vectors by input name (`query`, `related`, `unrelated`, `tone`) for the model file with [sha256], or null if there are none for it. */
    class Reference(val vectors: Map<String, FloatArray>) {
        /** Why [v] is wrong for input [name], or null if it matches (or there is no reference for that input). */
        fun mismatch(name: String, v: FloatArray): String? {
            val ref = vectors[name] ?: return null
            val c = cosine(v, ref)
            return if (c >= MIN_COSINE) null else "output for '$name' differs from the CPU reference (cosine ${"%.4f".format(c)}, at least $MIN_COSINE expected)"
        }
    }

    /** The sha256 of the embedder model file the reference must be made for ([com.therapylocal.app.models.ModelRegistry.EMBED]). */
    fun expectedSha(): String = com.therapylocal.app.models.ModelRegistry.EMBED.files.first().sha256

    fun parse(json: String, sha256: String): Reference? {
        val o = JSONObject(json)
        if (!o.optString("sha256").equals(sha256, ignoreCase = true)) return null
        val vs = o.getJSONObject("vectors")
        return Reference(vs.keys().asSequence().associateWith { k -> vs.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } })
    }

    fun load(context: Context, sha256: String): Reference? =
        runCatching { context.assets.open(ASSET).bufferedReader().use { parse(it.readText(), sha256) } }.getOrNull()
}
