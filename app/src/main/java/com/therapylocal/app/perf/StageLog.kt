package com.therapylocal.app.perf

import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * One structured line per pipeline stage (docs/AD/AD-22.md), the numbers a workflow comparison is built from: `evt=stage_done stage=<name>
 * workflow=<key> session=<id> wall_ms=<ms>` plus counts and, when set by the service, thermal status and memory. Counts and times only, never text.
 * The stages are `diarize`, `listen` (Fast) or `transcribe` (Quality, Canary), `translate`, `roles`, `facts` (window, extract and commit jobs, one
 * line each: sum them), `summary` and `translate_back` (the LOCALIZE job).
 */
object StageLog {
    const val TAG = "Stage"

    /** The stage a job of [kind] is part of; null for a kind with no stage of its own (cleanup, and transcribe, which logs its two stages itself). */
    fun stageOf(kind: String): String? = when (kind) {
        JobGraph.TRANSLATE -> "translate"
        JobGraph.ROLES -> "roles"
        JobGraph.WINDOW, JobGraph.EXTRACT, JobGraph.COMMIT -> "facts"
        JobGraph.SUMMARY -> "summary"
        JobGraph.LOCALIZE -> "translate_back"
        else -> null
    }

    /** Extra fields added to every line (thermal status, memory); the service sets it. */
    @Volatile
    var extras: () -> Array<Pair<String, Any?>> = { emptyArray() }

    fun done(stage: String, workflow: String, session: String, wallMs: Long, vararg fields: Pair<String, Any?>) {
        runCatching {
            AppLog.i(TAG, "stage_done", LogCtx.NONE, "stage" to stage, "workflow" to workflow, "session" to session, "wall_ms" to wallMs, *fields, *extras())
        }
    }

    /** Runs [block] and logs its wall time as [stage]. A block that throws logs nothing: the stage did not finish. */
    inline fun <T> timed(stage: String, workflow: String, session: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        done(stage, workflow, session, (System.nanoTime() - t0) / 1_000_000, *fields)
        return r
    }
}
