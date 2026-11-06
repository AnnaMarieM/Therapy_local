package com.therapylocal.app.engine

import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.models.ModelFile
import com.therapylocal.app.models.ModelSpec

/**
 * The one debug-only main-side seam (finding 23). Every function returns its argument unless [override] is set, which only androidTest
 * and `EngineLabActivity` do (through [install]). It swaps the model specs for `fake-llm`/`fake-embed`/`fake-asr`, makes
 * `ModelSetup.isReady` true, applies the plan's `"gate": "allow_all"` and passes the lab [EngineTuning] (catalogue 2.2).
 */
object LabWiring {
    /** What the lab changes. [allowAll] lifts the memory and availability gates (except a missing NPU library). */
    data class Lab(val tuning: EngineTuning = labTuning(1.0), val allowAll: Boolean = true, val fakeModels: Boolean = true, val ready: Boolean = true)

    @Volatile
    var override: Lab? = null

    /** The catalogue 2.2 tuning: short timings so a test needs seconds, every per-op limit times [scale]. */
    fun labTuning(scale: Double = 1.0): EngineTuning {
        fun s(ms: Long) = (ms * scale).toLong()
        return EngineTuning(
            openTimeoutMs = s(8_000), askMinTimeoutMs = s(10_000), embedTimeoutMs = s(3_000), transcribeTimeoutMs = s(5_000),
            closeTimeoutMs = s(2_000), hangNoProgressMs = s(2_000), hardCapFactor = 3, exitLookupMs = 2_000,
            backoffMs = listOf(250, 500, 1000, 2000), backoffCapMs = 4_000, idleMs = 1_000, liveShutdownMs = 2_000,
        )
    }

    /**
     * Arms a lab run in the main process: parses [planJson] (throws [FaultPlanException] on a bad plan), sets [Faults.plan], resets the
     * start counters and sets [override] from the plan (`gate`, `scale`). [tuning] replaces the catalogue tuning. Call [reset] after.
     */
    fun install(planJson: String?, tuning: EngineTuning? = null) {
        val plan = planJson?.let { FaultPlan.parse(it) }
        Faults.resetStarts()
        Faults.plan = planJson
        override = Lab(tuning = tuning ?: labTuning(plan?.scale ?: 1.0), allowAll = plan?.gate == "allow_all")
    }

    fun reset() {
        override = null
        Faults.plan = null
        Faults.resetStarts()
    }

    fun model(d: String): String = if (override?.fakeModels != true) d else when (d) {
        "gemma-4-e2b", "gemma-4-e4b", FakeRunners.LLM -> FakeRunners.LLM
        "embeddinggemma-2-740m", FakeRunners.EMBED -> FakeRunners.EMBED
        "canary-1b-v2", FakeRunners.ASR -> FakeRunners.ASR
        else -> d
    }

    fun spec(d: ModelSpec): ModelSpec {
        val id = model(d.id)
        if (id == d.id) return d
        return ModelSpec(id, id, null, listOf(ModelFile("$id.bin", "", 1L shl 20, "")))
    }

    /** True for the lab's fake model ids: they have no files on disk, so the completeness check is skipped. */
    fun isFake(id: String): Boolean = id == FakeRunners.LLM || id == FakeRunners.EMBED || id == FakeRunners.ASR

    fun ready(d: Boolean): Boolean = if (override?.ready == true) true else d

    fun gate(d: String?): String? = when {
        override?.allowAll != true -> d
        d != null && d.startsWith("dispatch library missing") -> d
        else -> null
    }

    fun tuning(d: EngineTuning): EngineTuning = override?.tuning ?: d
}
