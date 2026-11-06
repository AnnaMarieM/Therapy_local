package com.therapylocal.app.models

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import java.util.concurrent.ConcurrentHashMap

/**
 * How LiteRT-LM drives each [Accelerator] that says `litert = true`. A new chip that LiteRT-LM reaches (another vendor's NPU dispatch library,
 * say) is registered in [Accelerators] and bound here:
 *
 * ```
 * Accelerators.register(Accelerator(id = "MTK_APU", priority = 50, litert = true, available = { ctx -> ... }))
 * LiteRtBindings.bind("MTK_APU") { ctx -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir) }
 * ```
 */
object LiteRtBindings {
    private val bindings = ConcurrentHashMap<String, (Context) -> Backend>(
        mapOf(
            Accelerators.NPU.id to { ctx: Context -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir) },
            Accelerators.GPU.id to { _: Context -> Backend.GPU() },
            Accelerators.CPU.id to { _: Context -> Backend.CPU() },
        ),
    )

    fun bind(id: String, backend: (Context) -> Backend) {
        bindings[id.uppercase()] = backend
    }

    /** The LiteRT-LM backend for [id]; throws (caught by the ladder, which moves on) if the accelerator has no LiteRT-LM binding. */
    fun backend(context: Context, id: String): Backend =
        checkNotNull(bindings[id.uppercase()]) { "$id has no LiteRT-LM binding (LiteRtBindings.bind)" }(context)
}
