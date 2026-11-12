package com.therapylocal.app.qaapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.io.File

/**
 * The QA control API's transport (AD-16): `adb shell content call --uri content://<applicationId>.qaapi --method <command> --arg <json or b64:...>`.
 * Exported, but guarded twice: the manifest asks for android.permission.DUMP (the shell holds it, an ordinary app cannot) and every call checks
 * that the caller's uid is the shell's. The result is JSON; it is also written to `<external files>/api/<command>.json` for `adb pull`, and the
 * returned Bundle has `ok`, `file` and `bytes`, and `json` when the result is small (under [INLINE_MAX] bytes).
 * Only the command name, the result code and the size are logged, never the arguments or the result.
 */
class QaApiProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        val backend = QaAppBackend(ctx)
        val api = QaApi(backend) { Binder.getCallingUid() }
        val result = api.call(method, arg)
        val text = result.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val code = result.optJSONObject("error")?.optString("code") ?: "ok"
        val out = Bundle().putResult(result.optBoolean("ok"), code)
        // A refused caller gets the refusal and nothing is written for it.
        if (code != QaCode.FORBIDDEN_CALLER.code) {
            val file = File(File(backend.filesRoot, "api").apply { mkdirs() }, "${if (method in api.commandNames) method else "unknown"}.json")
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            out.putString("file", file.path); out.putInt("bytes", bytes.size)
        }
        if (bytes.size <= INLINE_MAX) out.putString("json", text)
        AppLog.i(QaAppBackend.TAG, "api_call", LogCtx.NONE, "cmd" to (if (method in api.commandNames) method else "unknown"), "code" to code, "bytes" to bytes.size)
        return out
    }

    private fun Bundle.putResult(ok: Boolean, code: String): Bundle = also { it.putBoolean("ok", ok); it.putString("code", code) }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val INLINE_MAX = 100_000
    }
}
