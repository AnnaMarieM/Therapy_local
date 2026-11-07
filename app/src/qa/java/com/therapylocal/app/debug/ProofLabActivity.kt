package com.therapylocal.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.therapylocal.app.engine.runner.LlmProof
import com.therapylocal.app.models.LiteRtBindings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * QA build only (issue #48). Runs the output-proof prompt, shorter and longer variants, and the MemProbe prompt on one engine that THIS activity
 * creates in the main process, so the model file, the activation type and the cache dir can be chosen. Writes files/out/prooflab_<tag>.json
 * with the full replies (made-up text, no patient data).
 *   adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.ProofLabActivity --es backend GPU --es file gemma-4-E2B-it.litertlm \
 *     --es dtype FLOAT32 --ei ctx 4096 --es tag gpu32 [--es cases facts1,facts2,facts4,pad400,memprobe,file:<name>] [--ei topk 1 --es temp 0.0 --es topp 1.0 --ei maxout 400]
 *   file:<name> reads files/prompts/<name>.sys and <name>.usr (candidate proof prompts, made-up text).
 */
@OptIn(ExperimentalApi::class)
class ProofLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        thread(name = "prooflab") { run(i) }
    }

    private fun run(i: android.content.Intent) {
        val backend = i.getStringExtra("backend") ?: "GPU"
        val file = i.getStringExtra("file") ?: "gemma-4-E2B-it.litertlm"
        val dtype = i.getStringExtra("dtype") ?: "default"
        val ctx = i.getIntExtra("ctx", 4096)
        val tag = i.getStringExtra("tag") ?: "${backend}_$dtype"
        val topK = i.getIntExtra("topk", 1)
        val temp = (i.getStringExtra("temp") ?: "0.0").toDouble()
        val topP = (i.getStringExtra("topp") ?: "1.0").toDouble()
        val maxOut = i.getIntExtra("maxout", 400)
        val cases = (i.getStringExtra("cases") ?: "facts1,facts2,facts3,facts4,memprobe").split(',')
        val result = JSONObject().put("backend", backend).put("file", file).put("dtype", dtype).put("ctx", ctx).put("topk", topK).put("temp", temp).put("topp", topP).put("maxout", maxOut)
        val out = JSONArray().also { result.put("cases", it) }
        ExperimentalFlags.enableBenchmark = true
        try {
            val cacheDir = File(cacheDir, "lab_$tag").apply { mkdirs() }.path
            val model = File(getExternalFilesDir(null), "models/$file")
            val act = if (dtype == "default") null else ActivationDataType.valueOf(dtype)
            val cfg = EngineConfig(modelPath = model.path, backend = LiteRtBindings.backend(this, backend), audioBackend = null, visionBackend = null, maxNumTokens = ctx, cacheDir = cacheDir, activationDataType = act)
            val t0 = System.nanoTime()
            val e = Engine(cfg)
            e.initialize()
            result.put("init_ms", (System.nanoTime() - t0) / 1_000_000)
            e.use {
                for (c in cases) {
                    val (system, user) = promptFor(c)
                    val c0 = System.nanoTime()
                    val rec = JSONObject().put("case", c).put("prompt_chars", user.length)
                    try {
                        val conv = e.createConversation(ConversationConfig(
                            systemInstruction = system?.let { Contents.of(it) },
                            samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temp, seed = 1), maxOutputToken = maxOut,
                        ))
                        conv.use { cv ->
                            val reply = cv.sendMessage(user).toString()
                            val b = cv.getBenchmarkInfo()
                            val verdict = if (c == "facts4" || c.startsWith("pad")) LlmProof.check(reply) else if (c == "proof2") LlmProof.checkFacts(reply) else null
                            rec.put("ms", (System.nanoTime() - c0) / 1_000_000).put("prefill_tokens", b.lastPrefillTokenCount).put("prefill_tok_s", b.lastPrefillTokensPerSecond)
                                .put("decode_tokens", b.lastDecodeTokenCount).put("decode_tok_s", b.lastDecodeTokensPerSecond).put("reply_chars", reply.length)
                                .put("verdict", verdict?.toString()).put("reply", reply)
                        }
                    } catch (t: Throwable) {
                        rec.put("error", t.javaClass.name + ": " + t.message?.take(300))
                    }
                    out.put(rec)
                    Log.i(TAG, "case=$c ${rec.toString().take(1500)}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "lab failed", t)
            result.put("error", t.javaClass.name + ": " + t.message?.take(600))
        }
        File(getExternalFilesDir(null), "out").apply { mkdirs() }.resolve("prooflab_$tag.json").writeText(result.toString(1))
        Log.i(TAG, "DONE tag=$tag")
        finish()
    }

    /** facts<N>: the proof prompt with the first N facts; facts4 is the shipped proof. pad<W>: the shipped proof after W words of made-up filler. memprobe: the #48 prompt. */
    private fun promptFor(c: String): Pair<String?, String> = when {
        c == "memprobe" -> MEMSYS to MEMUSER
        c == "proof2" -> LlmProof.SYSTEM2 to LlmProof.USER2
        c.startsWith("file:") -> File(getExternalFilesDir(null), "prompts/${c.removePrefix("file:")}").let { File(it.path + ".sys").readText().trim() to File(it.path + ".usr").readText().trim() }
        c.startsWith("facts") -> LlmProof.SYSTEM to LlmProof.FACTS.take(c.removePrefix("facts").toInt()).joinToString("\n")
        c.startsWith("pad") -> LlmProof.SYSTEM to padded(c.removePrefix("pad").toInt())
        else -> error("unknown case $c")
    }

    private fun padded(padWords: Int): String {
        val filler = StringBuilder()
        var w = 0
        while (w < padWords) { filler.append(FILLER).append(' '); w += FILLER.split(' ').size }
        return "Nepodstatný text, ignoruj ho:\n$filler\n\nVěty k převedení:\n" + LlmProof.USER
    }

    companion object {
        const val TAG = "PROOFLAB"
        private const val FILLER = "Ráno bylo chladné a mlhavé, na náměstí se pomalu otevíraly obchody a po dláždění projížděl první tramvajový vůz směrem k nádraží."
        private const val MEMSYS = "You are a careful note-taker for a psychotherapist. List the facts the client stated, one JSON object per line with keys heading, content, evidence (an exact quote). Write heading and content in Czech."
        private const val MEMUSER = "Klient: Petra\n\nPetra: Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené. Usínám až po půlnoci a v pět ráno se budím. Pila jsem proto víc kávy, tři až čtyři hrnky denně. V pátek mi šéf řekl, že dostanu výpověď. Poslední den budu mít 30. září. Bojím se, jak zaplatím nájem, platím osm tisíc měsíčně. Chodím na dlouhé procházky podél řeky."
    }
}
