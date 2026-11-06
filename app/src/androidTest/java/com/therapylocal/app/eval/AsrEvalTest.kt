package com.therapylocal.app.eval

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.ModelRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Not a pass/fail test: runs real recordings through the real Canary model and writes what they said, how long it took and on which
 * provider, to `<external files>/eval/results.txt`. Needs the models in `<external files>/models` and 16 kHz mono wavs named
 * `<lang>_<name>.wav` in `<external files>/eval` (see qa/asr-eval.sh). Skipped when there is nothing to run.
 *
 * Arguments (instrumentation -e): providers=cpu,nnapi  seconds=60
 */
@RunWith(AndroidJUnit4::class)
class AsrEvalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(context.getExternalFilesDir(null), "eval")

    @Test
    fun transcribeTheEvalRecordings() {
        val wavs = dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in $dir", wavs.isNotEmpty())
        val providers = (args.getString("providers") ?: "cpu").split(",")
        val seconds = (args.getString("seconds") ?: "60").toInt()
        val verbose = args.getString("verbose") == "1"
        val out = StringBuilder()

        for (provider in providers) for (lang in listOf(SpeechLanguage.Czech, SpeechLanguage.English)) {
            val group = wavs.filter { it.name.startsWith(if (lang == SpeechLanguage.Czech) "cs_" else "en_") }
            if (group.isEmpty()) continue
            val spec = ModelRegistry.CANARY_1B_V2
            if (!spec.isReady(context)) { out.line("SKIP ${lang.code} ${spec.id}: model not on the phone").line(""); continue }
            val asr: com.therapylocal.app.asr.AsrEngine = SpeechRecogniser(context, lang, com.therapylocal.app.engine.LocalEngineHost(context), provider)
            try {
                runBlocking { asr.load() }
                var errors = 0; var words = 0; var decode = 0L; var audio = 0.0; var scored = 0
                val detail = StringBuilder()
                for (wav in group) {
                    val pcm = readWav(wav).let { it.copyOf(minOf(it.size, seconds * 16_000)) }
                    val text = StringBuilder()
                    chunks(pcm).forEach { c ->
                        val r = runBlocking { asr.transcribe(c, LogCtx.NONE) }
                        decode += r.wallMs
                        text.append(r.text).append(' ')
                    }
                    audio += pcm.size / 16_000.0
                    val hyp = text.toString().trim()
                    val refFile = File(wav.parentFile, wav.nameWithoutExtension + ".txt")
                    if (refFile.exists()) {
                        val ref = refFile.readText()
                        val (e, n) = wordErrors(ref, hyp)
                        errors += e; words += n; scored++
                        if (verbose || e.toDouble() / n > 0.25) detail.line("  ${wav.name} wer ${"%.0f".format(100.0 * e / n)}%").line("    ref: ${ref.trim()}").line("    hyp: $hyp")
                    } else detail.line("  ${wav.name}: $hyp")
                }
                val wer = if (words > 0) "WER ${"%.1f".format(100.0 * errors / words)}% over $scored clips ($words words) | " else ""
                out.line("== ${lang.code} | ${spec.id} | $provider | ${wer}rtf ${"%.2f".format(decode / 1000.0 / audio)} | ${"%.0f".format(audio)}s audio")
                out.append(detail).line("")
            } catch (t: Throwable) {
                out.line("FAIL ${lang.code} ${spec.id} $provider: ${t.javaClass.simpleName}: ${t.message}").line("")
            } finally {
                asr.close()
            }
        }
        File(dir, "results.txt").writeText(out.toString())
        Log.i("EVAL", out.toString())
    }

    /** Word errors (substitutions + insertions + deletions) of [hyp] against [ref] and the number of reference words; punctuation and case are ignored. */
    private fun wordErrors(ref: String, hyp: String): Pair<Int, Int> {
        fun words(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val r = words(ref); val h = words(hyp)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1).also { it[0] = i }
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[h.size] to r.size
    }

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** The app's chunking: 20 s windows 15 s apart. */
    private fun chunks(pcm: ShortArray): List<ShortArray> {
        val size = 20 * 16_000
        val step = 15 * 16_000
        val list = ArrayList<ShortArray>()
        var at = 0
        while (at < pcm.size) {
            list += pcm.copyOfRange(at, minOf(pcm.size, at + size))
            if (at + size >= pcm.size) break
            at += step
        }
        return list
    }

    /** 16 kHz mono PCM16 wav: skips the chunks before "data". */
    private fun readWav(f: File): ShortArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4)
            val len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(len.toLong() and 0xffffffffL, (b.size - p - 8).toLong()).toInt() / 2
                val s = ShortArray(n)
                ByteBuffer.wrap(b, p + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                return s
            }
            p += 8 + len
        }
        error("no data chunk in ${f.name}")
    }
}
