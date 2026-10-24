package com.therapylocal.app.models

import android.content.Context
import java.io.File

/**
 * LiteRT-LM reaches a Qualcomm NPU only through libLiteRtDispatch_Qualcomm.so (plus the QAIRT libraries) in the app's native
 * library dir. The litertlm-android AAR does not ship it: https://github.com/google-ai-edge/LiteRT/issues/6889
 * Without it, asking for Backend.NPU either fails or silently runs elsewhere, so we do not claim NPU unless it is there.
 */
object NpuRuntime {
    const val DISPATCH_LIB = "libLiteRtDispatch_Qualcomm.so"

    fun available(context: Context): Boolean = File(context.applicationInfo.nativeLibraryDir, DISPATCH_LIB).exists()
}
