package com.therapylocal.app.models

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** The runtimes that load models, each binding an [Accelerator] its own way. */
enum class ModelRuntime { LITERT, ONNX }

/**
 * One kind of hardware a model can run on: the NPU, the GPU, the CPU, or a dedicated chip for some models only.
 *
 * Adding one (a vendor NPU with its own LiteRT dispatch library, an ONNX execution provider, an accelerator only one model was compiled for)
 * is one [Accelerators.register] call: give it an [id], a [priority], say whether it takes system memory, which [models] it serves and when
 * it is [available]. Then bind it to the runtime that drives it: [onnxProvider] here for ONNX models, or [LiteRtBindings.bind] for LiteRT-LM
 * (kept apart because LiteRT-LM's classes cannot load in JVM unit tests). Everything else comes with it: the try-in-order ladder with the CPU
 * behind it, the free-memory gate, the per-device history ([BackendGuard]: attempting, proven on this device, blocked), the settings screen and
 * the logs.
 */
data class Accelerator(
    /** Upper case, stable: it is stored per device and written in every log line ("NPU", "GPU", "CPU", "MTK_APU"…). */
    val id: String,
    /** Lower is tried first. The built-ins are NPU 100, GPU 200, CPU 1000; a dedicated chip usually goes before them. */
    val priority: Int,
    /** True for the CPU: always tried last, never blocked. There must be one. */
    val fallback: Boolean = false,
    /** Model ids it serves (as in [ModelSpec.id], without "+audio"); null serves every model. A chip built for one model lists only that. */
    val models: Set<String>? = null,
    /** Why it cannot be used on this phone (a library or device feature missing), or null when it can. Must not throw, must be cheap. */
    val available: (Context) -> String? = { null },
    /** Why it cannot serve this model on this phone (no model file built for the device, say), or null. Same rules as [available]. */
    val availableFor: (Context, String) -> String? = { _, _ -> null },
    /** Whether LiteRT-LM can drive it; the backend itself comes from [LiteRtBindings]. */
    val litert: Boolean = false,
    /** The ONNX Runtime execution provider name sherpa-onnx is given for it, or null if ONNX models cannot use it. */
    val onnxProvider: String? = null,
    /** Tried on its own in the automatic order; false keeps it for explicit requests (an evaluation, the debug override) only. */
    val auto: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id == id.uppercase()) { "Accelerator id must be non-blank upper case: '$id'" }
        require(!id.contains('@') && !id.contains('|') && !id.contains(',') && !id.contains(';')) { "Accelerator id '$id' has a reserved character" }
    }

    fun serves(model: String) = models == null || model.substringBefore('+') in models

    fun supports(runtime: ModelRuntime) = when (runtime) {
        ModelRuntime.LITERT -> litert
        ModelRuntime.ONNX -> onnxProvider != null
    }
}

/** The accelerators this build knows, in one place. See [Accelerator] for adding one. */
object Accelerators {
    val NPU = Accelerator(
        id = "NPU", priority = 100,
        // The Qualcomm dispatch and QNN libraries ship in the APK; a model needs an NPU build validated for this SoC (see NpuRuntime, AD-09).
        available = { ctx -> NpuRuntime.runtimeReason(ctx) },
        availableFor = { ctx, model -> NpuRuntime.modelReason(ctx, model) },
        litert = true,
    )
    val GPU = Accelerator(id = "GPU", priority = 200, litert = true)
    /** Android's NNAPI through ONNX Runtime: deprecated and not faster than the CPU for our speech models (AsrEvalTest), so opt-in only. */
    val NNAPI = Accelerator(id = "NNAPI", priority = 150, onnxProvider = "nnapi", auto = false)
    val CPU = Accelerator(id = "CPU", priority = 1000, fallback = true, litert = true, onnxProvider = "cpu")

    private val registered = CopyOnWriteArrayList(listOf(NPU, GPU, NNAPI, CPU))

    /** Adds [a], or replaces the one with its id. Call it before models load (Application.onCreate). */
    @Synchronized
    fun register(a: Accelerator) {
        require(!a.fallback || registered.none { it.fallback && it.id != a.id }) { "There is one fallback accelerator (the CPU)" }
        registered.removeAll { it.id == a.id }
        registered += a
    }

    /** Removes one that is not the fallback; tests and experiments only. */
    @Synchronized
    fun unregister(id: String) {
        require(get(id)?.fallback != true) { "The fallback cannot be removed" }
        registered.removeAll { it.id == id.uppercase() }
    }

    fun all(): List<Accelerator> = registered.sortedWith(compareBy({ it.fallback }, { it.priority }, { it.id }))

    fun get(id: String): Accelerator? = registered.firstOrNull { it.id == id.uppercase() }

    val fallback: Accelerator get() = registered.first { it.fallback }

    /** The fallback (the CPU) is never blocked. An id this build does not know is not the fallback: it can be blocked like any accelerator. */
    fun isFallback(id: String) = get(id)?.fallback == true

    /** Where [id] sorts: by priority, the fallback last, unknown ids after the known ones. */
    fun rank(id: String): Int = get(id)?.let { if (it.fallback) Int.MAX_VALUE - 1 else it.priority } ?: (Int.MAX_VALUE - 2)

    /** The order to try for [model] on [runtime]: every automatic accelerator that serves the model and that the runtime can bind, fallback last. */
    fun order(model: String, runtime: ModelRuntime): List<String> = all().filter { (it.auto || it.fallback) && it.serves(model) && it.supports(runtime) }.map { it.id }

    /** Why [id] is not tried for [model] on [runtime] now, apart from memory; null when it can be. Never throws. */
    fun unavailable(context: Context, id: String, model: String, runtime: ModelRuntime): String? {
        val a = get(id) ?: return "unknown accelerator $id"
        if (!a.serves(model)) return "${a.id} is dedicated to ${a.models?.joinToString()}"
        if (!a.supports(runtime)) return "${a.id} has no ${runtime.name} binding"
        return try {
            a.available(context) ?: a.availableFor(context, model.substringBefore('+'))
        } catch (t: Throwable) {
            "availability check failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
