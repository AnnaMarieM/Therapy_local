package com.therapylocal.app.engine

import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.models.ModelSpec

/** The debug lab's main-side override, as the identity: release, feel and qa have nothing to override. The real one lives in src/debug. */
object LabWiring {
    fun spec(d: ModelSpec): ModelSpec = d
    fun model(d: String): String = d
    fun isFake(id: String): Boolean = false

    fun ready(d: Boolean): Boolean = d
    fun gate(d: String?): String? = d
    fun tuning(d: EngineTuning): EngineTuning = d
}
