package com.therapylocal.app.engine.stress

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.RemoteEngineHost
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.Backends
import org.json.JSONObject
import java.io.File

/**
 * `dumpsys dropbox` entries (U0). System crash and ANR reports of the app, with the process each names. The dumps cost 0.5-1.5 s, so the lab
 * runs them per class, or per test only when the test failed or killed something (finding 30).
 *
 *     val t0 = Dropbox.now()
 *     ... a host crashes ...
 *     val e = Dropbox.entries(t0, "data_app_native_crash")
 *     assertTrue(e.all { it.process in hostNames })                  // or: Dropbox.assertMainIntact(t0)
 */
object Dropbox {
    class Entry(val stamp: String, val tag: String, val process: String?, val text: String)

    const val CRASH = "data_app_crash"
    const val NATIVE = "data_app_native_crash"
    const val ANR = "data_app_anr"

    private val HEADER = Regex("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\s+(\\S+)\\s+\\(")

    /** Device local time, `yyyy-MM-dd HH:mm:ss`: the format of the dump headers, which compare as strings. */
    fun now(): String = Shell.out("date '+%Y-%m-%d %H:%M:%S'").trim()

    /** Entries with the given [tags] (default: app crash, native crash and ANR) at or after [sinceStamp] (from [now]). */
    fun entries(sinceStamp: String, vararg tags: String): List<Entry> {
        val use = tags.toList().ifEmpty { listOf(CRASH, NATIVE, ANR) }
        val dump = Shell.out("dumpsys dropbox --print ${use.joinToString(" ")}")
        val out = ArrayList<Entry>()
        var head: MatchResult? = null
        val body = StringBuilder()
        fun flush() {
            head?.let { h ->
                val text = body.toString()
                out += Entry(h.groupValues[1], h.groupValues[2], Regex("(?m)^Process: (\\S+)").find(text)?.groupValues?.get(1), text)
            }
            body.clear()
        }
        for (line in dump.lines()) {
            val m = HEADER.find(line)
            if (m != null) { flush(); head = m } else body.appendLine(line)
        }
        flush()
        return out.filter { it.stamp >= sinceStamp && it.tag in use }
    }

    /** U0: no ANR entry names main, and crash entries name only `:live`/`:batch`. */
    fun assertMainIntact(sinceStamp: String) {
        val pkg = ExitInfos.main
        val bad = entries(sinceStamp).filter { e ->
            when (e.tag) {
                ANR -> e.process == pkg || e.process == null
                else -> e.process == pkg
            }
        }
        if (bad.isNotEmpty()) throw AssertionError("dropbox has entries for main: " + bad.joinToString { "${it.stamp} ${it.tag} ${it.process}" })
    }

    /** `tag:process` strings for the `dropbox` section of the data bundle. */
    fun tags(entries: List<Entry>): List<String> = entries.map { "${it.tag}:${it.process}" }
}

/** U0's window part: no "isn't responding" / "keeps stopping" dialog has focus. */
object Windows {
    private val BAD = Regex("isn't responding|keeps stopping|has stopped|Application Error|AppErrorDialog|Application Not Responding", RegexOption.IGNORE_CASE)

    fun focus(): String = Shell.out("dumpsys window | grep -E \"mCurrentFocus|mFocusedApp\"")

    fun assertNoErrorDialog() {
        val f = focus()
        if (BAD.containsMatchIn(f)) throw AssertionError("an error dialog has focus: $f")
    }
}

/**
 * L5, no patient text in what we write. The canaries are placed in prompts, facts and fake transcripts; they must appear in no log file of
 * ours, no logcat line, and no file the host uid can write (`files/`, `cache/`, external files). System tombstones, dropbox entries and
 * `AndroidRuntime` crash lines are excluded (finding 26, documented residual risk); crash-fault plans therefore carry no canary.
 *
 *     val c = Canary.make("A07")                      // CANARY-PT-7f3a-A07-<random>
 *     lab.startScenario(Scenario.PROCESSING, plan, canary = c)
 *     ...
 *     Canary.assertClean(listOf(c))                   // lab does this for canaries passed to EngineLab(canaries = ...)
 */
object Canary {
    const val PREFIX = "CANARY-PT-7f3a"
    private val rnd = java.util.Random()

    fun make(label: String): String = "$PREFIX-$label-" + "%06x".format(rnd.nextInt(0xFFFFFF))

    private val EXCLUDED_TAGS = Regex("AndroidRuntime|DEBUG|tombstoned|libc|crash_dump|ActivityManager|DropBox")

    /** Files under the app's writable roots that contain any of [canaries]. Files over 32 MB are skipped. */
    fun scanFiles(canaries: List<String>, roots: List<File> = defaultRoots()): List<String> {
        val hits = ArrayList<String>()
        fun walk(f: File) {
            if (f.isDirectory) { f.listFiles()?.forEach(::walk); return }
            if (f.length() > 32L shl 20) return
            val text = runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return
            if (canaries.any { text.contains(it) }) hits += f.path
        }
        roots.forEach(::walk)
        return hits
    }

    fun defaultRoots(): List<File> {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        return listOfNotNull(c.filesDir, c.cacheDir, c.noBackupFilesDir, c.getExternalFilesDir(null), c.externalCacheDir).distinct()
    }

    /** Logcat lines (all buffers) carrying a canary, except crash-reporter lines. */
    fun scanLogcat(canaries: List<String>): List<String> {
        val pats = canaries.joinToString(" ") { "-e ${Shell.quote(it)}" }
        return Shell.out("logcat -d -b all | grep -F $pats")
            .lines().filter { it.isNotBlank() && !EXCLUDED_TAGS.containsMatchIn(it) && !it.contains("grep -F") }
    }

    fun assertClean(canaries: List<String>, files: Boolean = true, logcat: Boolean = true) {
        if (canaries.isEmpty()) return
        val f = if (files) scanFiles(canaries) else emptyList()
        val l = if (logcat) scanLogcat(canaries) else emptyList()
        if (f.isNotEmpty() || l.isNotEmpty()) throw AssertionError("patient text canary found: files=$f logcat=${l.take(5).map { it.take(200) }}")
    }
}

/**
 * U4, the UI never shows machine text. [ERR_TEXT] is the catalogue's pattern. [assertClean] works on any list of strings;
 * [ComposeTestRule.visibleTexts] collects what Compose shows (text, content descriptions, editable text), unmerged, so nothing is hidden by merging.
 *
 *     composeRule.assertNoErrText()                    // U4 on the current screen
 *     ErrText.assertClean(listOf(notification.text))   // the same pattern for a notification
 */
object ErrText {
    val ERR_TEXT = Regex("(Exception|DeadObject|Binder|SIG[A-Z]{3,4}|native|stack|\\bnull\\b)")

    fun hits(texts: List<String>): List<String> = texts.filter { ERR_TEXT.containsMatchIn(it) }

    fun assertClean(texts: List<String>, where: String = "screen") {
        val h = hits(texts)
        if (h.isNotEmpty()) throw AssertionError("machine text on the $where: " + h.take(5).joinToString(" | ") { it.take(200) })
    }
}

/** Every string the Compose semantics tree shows right now. */
fun ComposeTestRule.visibleTexts(): List<String> {
    val any = SemanticsMatcher("any node") { true }
    return onAllNodes(any, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        val c = n.config
        buildList {
            c.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            c.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
            c.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        }
    }
}

fun ComposeTestRule.assertNoErrText() = ErrText.assertClean(visibleTexts())

/**
 * Watches this app's notifications (U1, U3): polls `NotificationManager.activeNotifications` and records every distinct (id, category, text).
 * Own notifications only, no permission needed.
 *
 *     val notes = NotificationWatch().also { it.start() }
 *     ... job runs ...
 *     val seen = notes.stop()
 *     notes.assertNoErrorCategory(); notes.assertTextClean()
 */
class NotificationWatch(private val periodMs: Long = 100) {
    class Seen(val atMs: Long, val id: Int, val category: String?, val title: String?, val text: String?)

    private val seen = ArrayList<Seen>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        running = true
        thread = Thread({
            while (running) {
                runCatching {
                    for (sbn in nm.activeNotifications) {
                        val n = sbn.notification
                        val e = n.extras
                        val s = Seen(SystemClock.elapsedRealtime(), sbn.id, n.category, e.getCharSequence(Notification.EXTRA_TITLE)?.toString(), e.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                        synchronized(seen) { if (seen.none { it.id == s.id && it.category == s.category && it.text == s.text && it.title == s.title }) seen += s }
                    }
                }
                SystemClock.sleep(periodMs) // the polling period itself
            }
        }, "notification-watch").apply { isDaemon = true; start() }
    }

    fun stop(): List<Seen> {
        running = false
        thread?.join(2_000)
        return all()
    }

    fun all(): List<Seen> = synchronized(seen) { seen.toList() }

    fun assertNoErrorCategory() {
        val bad = all().filter { it.category == Notification.CATEGORY_ERROR }
        if (bad.isNotEmpty()) throw AssertionError("error notification shown: " + bad.joinToString { "${it.title}/${it.text}" })
    }

    fun assertSawCategory(category: String) {
        if (all().none { it.category == category }) throw AssertionError("no notification of category $category; saw ${all().map { it.category }}")
    }

    fun assertTextClean() = ErrText.assertClean(all().flatMap { listOfNotNull(it.title, it.text) }, "notification")
}

/**
 * The guard store before and after a test (L3, L4). Enumerates every record the guard holds (`BackendGuard.all()`, so the keys are the
 * guard's own, whatever model ids the ladder used) as `state strikes everProven`; timestamps and reasons are left out.
 *
 * L4 "no strike" compares only the [faults] view: records with strikes or in a failed, died or blocked state. A successful load adds a
 * `PROVEN` record, which is not a strike.
 *
 *     val before = GuardSnapshot.take()
 *     ... scenario ...
 *     GuardSnapshot.take().assertSame(before)                  // L4: no new strike, no new failed/died/blocked record
 *     GuardSnapshot.take().assertFault("fake-llm|GPU", State.DIED, strikes = 1)
 */
class GuardSnapshot(val entries: Map<String, Rec>) {
    data class Rec(val state: BackendGuard.State, val strikes: Int, val everProven: Boolean) {
        override fun toString() = "$state strikes=$strikes proven=$everProven"
    }

    /** Records that count against a backend. */
    val faults: Map<String, Rec>
        get() = entries.filterValues { it.strikes > 0 || it.state == BackendGuard.State.FAILED || it.state == BackendGuard.State.DIED || it.state == BackendGuard.State.BLOCKED }

    /** L4: the fault view equals [before]'s. */
    fun assertSame(before: GuardSnapshot) {
        if (faults != before.faults) throw AssertionError("guard faults changed: before=${before.faults} after=$faults")
    }

    fun assertFault(key: String, state: BackendGuard.State, strikes: Int? = null) {
        val r = entries[key] ?: throw AssertionError("no guard record $key in ${entries.keys}")
        if (r.state != state || (strikes != null && r.strikes != strikes)) throw AssertionError("guard $key is $r, wanted $state strikes=$strikes")
    }

    /** Entries of this snapshot that differ from [before] (for the `guard` section). */
    fun diff(before: GuardSnapshot): Map<String, String> =
        (entries.keys + before.entries.keys).filter { entries[it] != before.entries[it] }.associateWith { entries[it]?.toString() ?: "(none)" }

    fun toJson(): JSONObject = JSONObject().also { o -> entries.forEach { (k, v) -> o.put(k, v.toString()) } }

    companion object {
        /** `model|BACKEND` to record, for every record in the guard. */
        fun take(): GuardSnapshot = GuardSnapshot(
            guard().all().mapNotNull { e -> e.record?.let { "${e.model}|${e.backend}" to Rec(it.state, it.strikes, it.everProven) } }.toMap(),
        )

        fun guard(): BackendGuard = Backends.guard(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}

/** Drops the process-wide host connections and the shared embedder, so the next engine call builds new ones with the current lab tuning. */
object Hosts {
    @Suppress("UNCHECKED_CAST")
    fun reset() {
        val hosts = field("hosts").get(Engines) as MutableMap<EngineRole, Any>
        hosts.values.forEach { (it as? RemoteEngineHost)?.let { h -> runCatching { h.release() } } }
        hosts.clear()
        val f = field("embedder")
        (f.get(Engines) as? Embedder)?.let { runCatching { it.close() } }
        f.set(Engines, null)
    }

    /** The remote host for [role] if one exists (never creates or binds one). */
    @Suppress("UNCHECKED_CAST")
    fun existing(role: EngineRole): RemoteEngineHost? = (field("hosts").get(Engines) as Map<EngineRole, Any>)[role] as? RemoteEngineHost

    private fun field(name: String) = Engines::class.java.getDeclaredField(name).apply { isAccessible = true }
}
