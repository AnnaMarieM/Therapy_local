package com.therapylocal.app

import android.content.Context
import java.io.File

object ModelConfig {
    const val NAME = "gemma-4-E2B-it.litertlm"
    const val URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$NAME"
    const val SIZE_BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }.resolve(NAME)

    fun partFile(context: Context): File = File(file(context).path + ".part")

    fun isReady(context: Context): Boolean = file(context).let { it.exists() && it.length() == SIZE_BYTES }
}
