package com.therapylocal.app.qaapi

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * The QA control API (docs/AD/AD-16.md, docs/QA_API.md). This whole package lives in the qa source set: no other build type compiles it,
 * and verifyNoQaApiInRelease fails the build if a trace of it (the marker strings "QaApi", "qaapi") shows up in the release or feel APK.
 */

const val API_VERSION = 1

/** The QA vault password every doc and script uses. It exists only here, in the qa source set. */
const val STANDARD_QA_PASSWORD = "12345678"
const val MIN_PASSWORD = 8

/** The Android uid of the adb shell. The only caller a command is accepted from (AD-16 point 2). */
const val SHELL_UID = 2000

/** Error codes of the JSON result. Stable: scripts match on them. */
enum class QaCode(val code: String) {
    BAD_REQUEST("bad_request"), UNKNOWN_COMMAND("unknown_command"), FORBIDDEN_CALLER("forbidden_caller"), NOT_QA_BUILD("not_qa_build"),
    VAULT_LOCKED("vault_locked"), VAULT_NOT_SET_UP("vault_not_set_up"), VAULT_ALREADY_SET_UP("vault_already_set_up"), BAD_PASSWORD("bad_password"),
    NOT_FOUND("not_found"), BAD_PATH("bad_path"), BUSY("busy"), FAILED("failed"),
}

class QaException(val code: QaCode, message: String) : RuntimeException(message)

enum class VaultState { NOT_SET_UP, LOCKED, UNLOCKED }

/** Everything a command needs from the app, so the dispatcher is plain JVM code and the tests give a fake. Each call goes to the same code the UI uses. */
interface QaBackend {
    val applicationId: String
    fun vaultState(): VaultState
    fun status(): JSONObject
    fun setup(password: String)
    fun unlock(password: String): Boolean
    fun seedClients(clients: List<QaClient>): JSONObject
    fun importSession(client: String, file: File, session: String?, date: String?, language: String?): JSONObject
    /** Blocks up to [timeoutMs] for the job queue to go idle; the result says whether it did. */
    fun waitIdle(timeoutMs: Long): JSONObject
    fun results(client: String?, session: String?, queries: JSONArray?): JSONObject
    fun swapRoles(session: String): JSONObject
    /** Every app setting with its allowed values and current value ([QaSettings]); each setting's setter is the app's own. */
    fun settings(): List<QaSetting>
    /** A session from a ready transcript instead of audio (the export's own format): its segments, optionally the speaker map, optionally queued from the window job on. */
    fun importTranscript(client: String, session: String, startedAt: Long?, language: String?, segments: List<QaSegment>, speakerMap: JSONObject?, process: Boolean): JSONObject
    /** The clients and sessions in the format [QaApi]'s import takes back (see docs/QA_API.md "Export and import"). */
    fun exportData(client: String?, session: String?): JSONObject
    /** One model call (op: transcribe, diarize, embed, ask) with its timings; [file] is the resolved audio file for the audio ops. */
    fun engineCall(op: String, args: JSONObject, file: File?, choice: BackendChoice): JSONObject
    /** Queues one pipeline job (or follows [jobId]) and waits up to [timeoutMs] for it to settle. */
    fun runJob(kind: String, session: String, jobId: Long?, choice: BackendChoice, timeoutMs: Long): JSONObject
    /** Wipes the vault and everything derived from it. Never the downloaded models. */
    fun reset(): JSONObject
    /** The folder a path argument must be inside (the app's external files dir). */
    val filesRoot: File
}

data class QaClient(val id: String, val name: String, val language: String)

data class QaSegment(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

object QaGuard {
    const val REAL_APP_ID = "com.therapylocal.app"

    /** Data-deleting commands, and every other command, run only on an application id ending in `.qa` (and never on the real app's). */
    fun isQaApplicationId(id: String): Boolean = id.endsWith(".qa") && id != REAL_APP_ID

    /** Only the adb shell user may call; every other app, and the app itself, is refused. */
    fun callerAllowed(uid: Int): Boolean = uid == SHELL_UID
}

object QaPaths {
    /** [path] as a file inside [root]: relative to it, or absolute and inside it. Anything else (`..`, symlinks out, other folders) is refused. */
    fun resolve(root: File, path: String): File {
        if (path.isBlank() || path.contains('\u0000')) throw QaException(QaCode.BAD_PATH, "path is empty or invalid")
        val base = root.canonicalFile
        val f = (if (path.startsWith("/")) File(path) else File(base, path)).canonicalFile
        if (f.path != base.path && !f.path.startsWith(base.path + File.separator)) throw QaException(QaCode.BAD_PATH, "path must be inside the app's files folder")
        if (!f.isFile) throw QaException(QaCode.NOT_FOUND, "no such file on the phone: ${f.name}")
        return f
    }
}

object QaArgs {
    const val MAX_BYTES = 1_000_000

    /** The call's argument: empty (no arguments), a JSON object, or `b64:` and a base64 JSON object (so a shell never has to quote it). */
    fun parse(arg: String?): JSONObject {
        val raw = arg?.trim().orEmpty()
        if (raw.isEmpty()) return JSONObject()
        if (raw.length > MAX_BYTES) throw QaException(QaCode.BAD_REQUEST, "argument too large")
        val text = if (raw.startsWith("b64:")) {
            try { String(Base64.getMimeDecoder().decode(raw.removePrefix("b64:")), Charsets.UTF_8) } catch (_: IllegalArgumentException) { throw QaException(QaCode.BAD_REQUEST, "argument is not valid base64") }
        } else raw
        return try { JSONObject(text) } catch (_: Exception) { throw QaException(QaCode.BAD_REQUEST, "argument is not a JSON object") }
    }

    fun string(a: JSONObject, key: String): String = a.optString(key, "").takeIf { it.isNotBlank() } ?: throw QaException(QaCode.BAD_REQUEST, "missing argument: $key")
    fun optString(a: JSONObject, key: String): String? = if (a.has(key) && !a.isNull(key)) a.optString(key).takeIf { it.isNotBlank() } else null
}

/**
 * The dispatcher: caller and build checks, argument parsing, the locked-vault rule, error mapping. Every result is a JSON object with
 * `api_version`, `command` and `ok`; a failure carries `error` {code, message}. Messages never contain clinical text.
 */
class QaApi(private val backend: QaBackend, private val callerUid: () -> Int) {
    private class Command(val needsUnlocked: Boolean, val run: (JSONObject) -> JSONObject)

    private val commands: Map<String, Command> = mapOf(
        "status" to Command(false) { backend.status() },
        "setup" to Command(false, ::setup),
        "unlock" to Command(false, ::unlock),
        "seed_clients" to Command(true, ::seedClients),
        "import_clients" to Command(true, ::seedClients),
        "import_session" to Command(true) { importOne(it) },
        "import_manifest" to Command(true, ::importManifest),
        "export" to Command(true) { backend.exportData(QaArgs.optString(it, "client"), QaArgs.optString(it, "session")) },
        "transcribe" to Command(true, ::transcribe),
        "diarize" to Command(true, ::diarize),
        "embed" to Command(true, ::embed),
        "ask" to Command(true, ::ask),
        "run_job" to Command(true, ::runJob),
        "wait" to Command(true, ::wait),
        "results" to Command(true, ::results),
        "swap_roles" to Command(true) { backend.swapRoles(QaArgs.string(it, "session")) },
        "get_settings" to Command(false) { QaSettings.get(backend.settings()) }, // settings are not clinical data: works with a locked vault
        "set_setting" to Command(true, ::setSetting),
        // alias of set_setting summary_format
        "set_summary_format" to Command(true) { QaSettings.set(backend.settings(), "summary_format", QaArgs.string(it, "format")).also { r -> r.put("format", r.get("new")) } },
        "reset" to Command(true) { backend.reset() },
    )

    val commandNames: Set<String> get() = commands.keys

    fun call(command: String, arg: String?): JSONObject {
        val out = JSONObject().put("api_version", API_VERSION).put("command", command)
        return try {
            if (!QaGuard.callerAllowed(callerUid())) throw QaException(QaCode.FORBIDDEN_CALLER, "only the adb shell user may call the QA API")
            if (!QaGuard.isQaApplicationId(backend.applicationId)) throw QaException(QaCode.NOT_QA_BUILD, "this is not the QA build")
            val cmd = commands[command] ?: throw QaException(QaCode.UNKNOWN_COMMAND, "unknown command; known: ${commands.keys.joinToString(",")}")
            val args = QaArgs.parse(arg)
            if (cmd.needsUnlocked) when (backend.vaultState()) {
                VaultState.NOT_SET_UP -> throw QaException(QaCode.VAULT_NOT_SET_UP, "the vault is not set up: call setup")
                VaultState.LOCKED -> throw QaException(QaCode.VAULT_LOCKED, "the vault is locked: call unlock")
                VaultState.UNLOCKED -> {}
            }
            out.put("ok", true).put("data", cmd.run(args))
        } catch (e: QaException) {
            fail(out, e.code, e.message.orEmpty())
        } catch (e: Exception) {
            // The class only: an exception message can quote clinical text (a JSON parse error, a SQL value).
            fail(out, QaCode.FAILED, "internal error: ${e.javaClass.simpleName}")
        }
    }

    private fun fail(out: JSONObject, code: QaCode, message: String): JSONObject =
        out.put("ok", false).put("error", JSONObject().put("code", code.code).put("message", message))

    private fun password(a: JSONObject): String = QaArgs.optString(a, "password") ?: STANDARD_QA_PASSWORD

    private fun setup(a: JSONObject): JSONObject {
        if (backend.vaultState() != VaultState.NOT_SET_UP) throw QaException(QaCode.VAULT_ALREADY_SET_UP, "the vault is already set up: unlock it, or reset")
        val pw = password(a)
        if (pw.length < MIN_PASSWORD) throw QaException(QaCode.BAD_REQUEST, "password needs at least $MIN_PASSWORD characters")
        backend.setup(pw)
        return JSONObject().put("unlocked", true)
    }

    private fun unlock(a: JSONObject): JSONObject {
        when (backend.vaultState()) {
            VaultState.NOT_SET_UP -> throw QaException(QaCode.VAULT_NOT_SET_UP, "the vault is not set up: call setup")
            VaultState.UNLOCKED -> return JSONObject().put("unlocked", true).put("already", true)
            VaultState.LOCKED -> {}
        }
        if (!backend.unlock(password(a))) throw QaException(QaCode.BAD_PASSWORD, "wrong password")
        return JSONObject().put("unlocked", true).put("already", false)
    }

    private fun setSetting(a: JSONObject): JSONObject {
        val value = if (a.has("value") && !a.isNull("value")) a.get("value").toString() else throw QaException(QaCode.BAD_REQUEST, "missing argument: value")
        return QaSettings.set(backend.settings(), QaArgs.string(a, "setting"), value)
    }

    private fun seedClients(a: JSONObject): JSONObject {
        val arr = a.optJSONArray("clients") ?: throw QaException(QaCode.BAD_REQUEST, "missing argument: clients (a list of {id, name, lang})")
        val list = (0 until arr.length()).map { i ->
            val c = arr.optJSONObject(i) ?: throw QaException(QaCode.BAD_REQUEST, "clients[$i] is not an object")
            val id = c.optString("id")
            if (!Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id)) throw QaException(QaCode.BAD_REQUEST, "clients[$i].id must be lowercase letters, digits and dashes")
            val name = c.optString("name").ifBlank { throw QaException(QaCode.BAD_REQUEST, "clients[$i].name is missing") }
            val lang = c.optString("lang", "en").ifBlank { "en" }
            if (!Regex("[a-z]{2,3}").matches(lang)) throw QaException(QaCode.BAD_REQUEST, "clients[$i].lang must be a language code")
            QaClient(id, name, lang)
        }
        if (list.isEmpty()) throw QaException(QaCode.BAD_REQUEST, "clients is empty")
        return backend.seedClients(list)
    }

    private val ID = Regex("[A-Za-z0-9_-]{1,80}")
    private val LANG = Regex("[a-z]{2,3}")
    private val ROLES = setOf("therapist", "patient", "other")

    /** One session: from `file` (audio on the phone, the normal pipeline) or from `segments` (a ready transcript). Also an entry of `import_manifest`. */
    private fun importOne(a: JSONObject): JSONObject {
        val client = QaArgs.string(a, "client")
        val session = QaArgs.optString(a, "session")?.also { if (!ID.matches(it)) throw QaException(QaCode.BAD_REQUEST, "session id: letters, digits, _ and - only") }
        val lang = QaArgs.optString(a, "language")?.also { if (!LANG.matches(it)) throw QaException(QaCode.BAD_REQUEST, "language must be a language code") }
        val date = QaArgs.optString(a, "date")?.also { if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) throw QaException(QaCode.BAD_REQUEST, "date must be yyyy-MM-dd") }
        val startedAt = if (a.has("started_at") && !a.isNull("started_at")) a.optLong("started_at", -1).takeIf { it >= 0 } ?: throw QaException(QaCode.BAD_REQUEST, "started_at must be epoch milliseconds") else null
        val segs = a.optJSONArray("segments")
        val hasFile = QaArgs.optString(a, "file") != null
        if (segs != null == hasFile) throw QaException(QaCode.BAD_REQUEST, "give exactly one of file (audio on the phone) or segments (a transcript)")
        if (segs == null) {
            val file = QaPaths.resolve(backend.filesRoot, QaArgs.string(a, "file"))
            return backend.importSession(client, file, session, date, lang)
        }
        if (session == null) throw QaException(QaCode.BAD_REQUEST, "missing argument: session (a transcript needs its id)")
        val list = (0 until segs.length()).map { i ->
            val g = segs.optJSONObject(i) ?: throw QaException(QaCode.BAD_REQUEST, "segments[$i] is not an object")
            val text = g.optString("text")
            if (text.isBlank()) throw QaException(QaCode.BAD_REQUEST, "segments[$i].text is empty")
            val s = if (g.has("startMs")) g.optLong("startMs") else g.optLong("start_ms")
            val e = if (g.has("endMs")) g.optLong("endMs") else g.optLong("end_ms")
            QaSegment(s, e, if (g.isNull("speaker") || !g.has("speaker")) null else g.optString("speaker").takeIf { it.isNotBlank() }, text)
        }
        if (list.isEmpty()) throw QaException(QaCode.BAD_REQUEST, "segments is empty")
        val map = a.optJSONObject("speaker_map")?.also { m -> m.keys().forEach { k -> if (m.optString(k).lowercase() !in ROLES) throw QaException(QaCode.BAD_REQUEST, "speaker_map roles: therapist, patient or other") } }
        val at = startedAt ?: date?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it)!!.time }.getOrNull() }
        return backend.importTranscript(client, session, at, lang, list, map, a.optBoolean("process", true))
    }

    /** A whole set in one call: `clients` (as seed_clients) and `sessions` (entries as import_session), in client and date order, so earlier sessions come first. */
    private fun importManifest(a: JSONObject): JSONObject {
        val seeded = if (a.optJSONArray("clients") != null) seedClients(a) else null
        val arr = a.optJSONArray("sessions") ?: JSONArray()
        val entries = (0 until arr.length()).map { arr.optJSONObject(it) ?: throw QaException(QaCode.BAD_REQUEST, "sessions[$it] is not an object") }
        fun key(e: JSONObject) = e.optString("client") + "\u0000" + (e.optString("date").ifEmpty { "%020d".format(e.optLong("started_at")) })
        val imported = JSONArray()
        val failed = JSONArray()
        for (e in entries.sortedBy(::key)) {
            if (a.has("process") && !e.has("process")) e.put("process", a.optBoolean("process"))
            try { imported.put(importOne(e)) } catch (x: QaException) {
                failed.put(JSONObject().put("client", e.optString("client")).put("session", e.optString("session")).put("code", x.code.code).put("message", x.message))
            }
        }
        return JSONObject().put("clients", seeded ?: JSONObject.NULL).put("imported", imported).put("failed", failed).put("count", imported.length())
    }

    private fun audio(a: JSONObject): File = QaPaths.resolve(backend.filesRoot, QaArgs.string(a, "file"))

    private fun transcribe(a: JSONObject): JSONObject {
        val file = audio(a)
        return backend.engineCall("transcribe", a, file, BackendChoice.parse(a))
    }

    private fun diarize(a: JSONObject): JSONObject = backend.engineCall("diarize", a, audio(a), BackendChoice.parse(a))

    private fun embed(a: JSONObject): JSONObject {
        val texts = a.optJSONArray("texts")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: listOf(QaArgs.string(a, "text"))
        if (texts.isEmpty() || texts.size > 64 || texts.any { it.isBlank() }) throw QaException(QaCode.BAD_REQUEST, "give text, or texts (1 to 64 non-empty strings)")
        return backend.engineCall("embed", a, null, BackendChoice.parse(a))
    }

    private fun ask(a: JSONObject): JSONObject {
        QaArgs.string(a, "user")
        return backend.engineCall("ask", a, null, BackendChoice.parse(a))
    }

    private fun runJob(a: JSONObject): JSONObject {
        val kind = QaArgs.string(a, "kind")
        if (kind !in JOB_KINDS) throw QaException(QaCode.BAD_REQUEST, "kind must be one of ${JOB_KINDS.joinToString(",")} (extract and commit are queued by window)")
        val id = if (a.has("job_id")) a.optLong("job_id") else null
        val s = a.optInt("timeout_s", DEFAULT_WAIT_S).coerceIn(1, MAX_WAIT_S)
        return backend.runJob(kind, QaArgs.string(a, "session"), id, BackendChoice.parse(a), s * 1000L)
    }

    private fun wait(a: JSONObject): JSONObject {
        val s = a.optInt("timeout_s", DEFAULT_WAIT_S)
        // Bounded per call, so no call blocks a binder thread for long; the host client loops (qa/qa-api.sh wait).
        return backend.waitIdle(s.coerceIn(1, MAX_WAIT_S) * 1000L)
    }

    private fun results(a: JSONObject): JSONObject = backend.results(QaArgs.optString(a, "client"), QaArgs.optString(a, "session"), a.optJSONArray("queries"))

    companion object {
        const val DEFAULT_WAIT_S = 20
        const val MAX_WAIT_S = 50
        val JOB_KINDS = listOf("transcribe", "roles", "window", "summary")
    }
}
