package com.therapylocal.app.models

/**
 * What this phone has shown it can run, per model and backend, kept across restarts.
 *
 * Every load writes a note **before** it starts ([attempting], then each native [phase]) and another once the backend passed its test
 * ([proven]). A caught failure is a strike ([failed]). The low-memory killer, a native crash or an ANR cannot be caught, so on the next start
 * [recover] turns an attempt still open, and a GPU/NPU engine that was loaded when the process was killed, into strikes; that is the only way
 * not to walk into the same kill on every start. An exit that was not the backend's doing (the user, a Java crash elsewhere, an unknown
 * reason) only counts after it happened [INTERRUPTED_STRIKE] times in a row during the same attempt.
 *
 * A backend that was never proven on this build is blocked after [BLOCK_UNPROVEN] strikes ("this device really cannot do this"); one that was
 * proven needs [BLOCK_PROVEN], since a failure there is more likely transient (memory pressure), and gets one more try after [BLOCK_RETRY_MS].
 * Strikes are cleared when the app closes a GPU/NPU engine itself ([unloaded]): it loaded and survived its work. A load alone does not clear
 * them, or a backend that loads fine and is then killed mid-work would be retried forever.
 *
 * A loaded engine that fails on [INFERENCE_STRIKE] real prompts in a row ([inferenceFailed]) gets a strike and is [demoted] for the rest of this
 * process, so the next load goes to the next backend instead of the same failing one.
 *
 * The fallback (CPU) is never blocked: it is the last resort. An app or system update ([build] changes) forgets everything, so a fixed driver
 * gets its chance again.
 */
class BackendGuard(private val store: Store, private val build: String, private val now: () -> Long = System::currentTimeMillis) {
    /** Durable key-value storage: [put] must have reached the disk when it returns (the next thing may be a kill). A null value removes. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
        fun keys(): Set<String>

        /** Several changes in one write; stores that can batch override this. */
        fun putAll(values: Map<String, String?>) = values.forEach { (k, v) -> put(k, v) }
    }

    enum class State { ATTEMPTING, PROVEN, FAILED, DIED, BLOCKED }

    data class Record(val state: State, val strikes: Int, val everProven: Boolean, val at: Long, val reason: String) {
        internal fun encode() = listOf(state.name, strikes, everProven, at, reason.replace('|', '/').take(MAX_REASON)).joinToString("|")

        companion object {
            internal fun decode(s: String?): Record? = s?.split('|', limit = 5)?.takeIf { it.size == 5 }?.let {
                runCatching { Record(State.valueOf(it[0]), it[1].toInt(), it[2].toBoolean(), it[3].toLong(), it[4]) }.getOrNull()
            }
        }
    }

    /** How the previous process ended, as far as the system reports it. [foreground]: it was visible or a foreground service. */
    data class LastExit(val kind: ExitKind, val foreground: Boolean, val description: String)

    enum class ExitKind { KILLED_FOR_MEMORY, NATIVE_CRASH, CRASH, ANR, BENIGN, UNKNOWN }

    /** One change worth logging. */
    data class Event(val evt: String, val model: String, val backend: String, val record: Record?, val detail: String? = null)

    /** A backend not tried on the last load, and why (not enough free memory, missing library): not a strike, the device was never asked. */
    data class Skip(val at: Long, val reason: String)

    /** What one model ran on and what each backend has shown on this phone, for the settings screen. [selected] is [NONE] when nothing loaded. */
    data class ModelView(val model: String, val selected: String?, val selectedAt: Long?, val backends: Map<String, Pair<Record?, Skip?>>)

    /** Backends that failed on real prompts in this process: skipped until the app restarts (not stored). */
    private val demoted = HashSet<String>()

    /** The backend the last load of [model] ended on. */
    @Synchronized
    fun selected(model: String, backend: String) {
        store.putAll(mapOf(SELECTED + model to "${backend.uppercase()}|${now()}", SKIP_PREFIX + pair(model, backend) to null))
    }

    /** The last load of [model] found no backend at all. */
    @Synchronized
    fun selectedNone(model: String) {
        store.put(SELECTED + model, "$NONE|${now()}")
    }

    /** Written only when the reason changed: a backend skipped on every load (the NPU without its library) must not cost a disk write each time. */
    @Synchronized
    fun skipped(model: String, backend: String, reason: String) {
        val key = SKIP_PREFIX + pair(model, backend)
        val r = reason.take(MAX_REASON)
        if (store.get(key)?.substringAfter('|', "") == r) return
        store.put(key, "${now()}|$r")
    }

    /** Every model this phone has loaded or tried, its accelerators in the order they are tried (see [Accelerators.rank]). */
    @Synchronized
    fun overview(): List<ModelView> {
        val keys = store.keys()
        val models = keys.mapNotNull { k ->
            when {
                k.startsWith(PREFIX) -> unpair(k.removePrefix(PREFIX))?.first
                k.startsWith(SKIP_PREFIX) -> unpair(k.removePrefix(SKIP_PREFIX))?.first
                k.startsWith(SELECTED) -> k.removePrefix(SELECTED)
                else -> null
            }
        }.toSortedSet()
        return models.map { m ->
            val sel = store.get(SELECTED + m)?.split('|', limit = 2)
            val backends = keys.mapNotNull { k ->
                when {
                    k.startsWith(PREFIX) -> unpair(k.removePrefix(PREFIX))
                    k.startsWith(SKIP_PREFIX) -> unpair(k.removePrefix(SKIP_PREFIX))
                    else -> null
                }?.takeIf { it.first == m }?.second
            }.toSortedSet(compareBy({ Accelerators.rank(it) }, { it }))
            ModelView(
                m, sel?.getOrNull(0), sel?.getOrNull(1)?.toLongOrNull(),
                backends.associateWith { b ->
                    record(m, b) to store.get(SKIP_PREFIX + pair(m, b))?.split('|', limit = 2)?.let { s -> Skip(s[0].toLongOrNull() ?: 0L, s.getOrElse(1) { "" }) }
                },
            )
        }
    }

    /**
     * Forgets every record and block on this phone, so every backend is tried and proven again (a manual "test again"). An attempt in
     * flight and a loaded engine stay noted: a kill right after the reset still counts.
     */
    @Synchronized
    fun reset() {
        store.putAll(store.keys().filter { it != BUILD && it != PENDING && it != RESIDENT }.associateWith { null })
        demoted.clear()
    }

    @Synchronized
    fun record(model: String, backend: String): Record? = Record.decode(store.get(key(model, backend)))

    /** Blocked on this phone: never proven and struck out, or proven, struck out and not yet due its retry after [BLOCK_RETRY_MS]. */
    @Synchronized
    fun blocked(model: String, backend: String): Boolean {
        val r = record(model, backend) ?: return false
        if (r.state != State.BLOCKED) return false
        return !(r.everProven && now() - r.at >= BLOCK_RETRY_MS)
    }

    /** Failed on real prompts in this process (see [inferenceFailed]). */
    @Synchronized
    fun demoted(model: String, backend: String): Boolean = pair(model, backend) in demoted

    /** Written before the engine is created. Several attempts can be open at once (two engines loading on different threads). */
    @Synchronized
    fun attempting(model: String, backend: String) {
        val r = record(model, backend)
        store.putAll(
            mapOf(
                key(model, backend) to Record(State.ATTEMPTING, r?.strikes ?: 0, r?.everProven ?: false, now(), "attempting").encode(),
                PENDING to (pending() + (pair(model, backend) to "start")).encodePending(),
            ),
        )
    }

    /** The native step an open attempt is in ("create", "initialize", "test"): a kill is then reported with the step that caused it. */
    @Synchronized
    fun phase(model: String, backend: String, phase: String) {
        val p = pending()
        if (pair(model, backend) !in p) return
        store.put(PENDING, (p + (pair(model, backend) to phase.filter { it.isLetterOrDigit() || it == '_' })).encodePending())
    }

    /** Written once the backend loaded and answered its test. Strikes are kept until a clean [unloaded]: loading is not yet surviving the work. */
    @Synchronized
    fun proven(model: String, backend: String) {
        val r = record(model, backend)
        store.putAll(
            mapOf(
                key(model, backend) to Record(State.PROVEN, r?.strikes ?: 0, true, now(), "proven on this device").encode(),
                PENDING to (pending() - pair(model, backend)).encodePending(),
                INTERRUPTED + pair(model, backend) to null,
            ),
        )
    }

    /** A caught failure while loading. */
    @Synchronized
    fun failed(model: String, backend: String, reason: String?): Record {
        store.put(PENDING, (pending() - pair(model, backend)).encodePending())
        return strike(model, backend, State.FAILED, reason ?: "error")
    }

    /**
     * A caught failure on a real prompt. The first is only noted (a bad prompt fails on every backend); [INFERENCE_STRIKE] in a row on a GPU/NPU
     * are a strike and demote the backend for this process. Returns the record when it was struck (the caller should reload elsewhere), else null.
     */
    @Synchronized
    fun inferenceFailed(model: String, backend: String, reason: String?): Record? {
        if (isCpu(backend)) return null
        val key = INFERENCE + pair(model, backend)
        val n = (store.get(key)?.toIntOrNull() ?: 0) + 1
        if (n < INFERENCE_STRIKE) {
            store.put(key, n.toString())
            record(model, backend)?.let { put(model, backend, it.copy(at = now(), reason = "proven on this device; last prompt failed: ${reason ?: "error"}")) }
            return null
        }
        store.put(key, null)
        demoted += pair(model, backend)
        return strike(model, backend, State.FAILED, "failed on $n prompts in a row: ${reason ?: "error"}")
    }

    /** A prompt answered: the run of failures is over. Writes only when there was one. */
    @Synchronized
    fun inferenceOk(model: String, backend: String) {
        val key = INFERENCE + pair(model, backend)
        if (store.get(key) != null) store.put(key, null)
    }

    /** A GPU/NPU engine is resident from now until [unloaded]. */
    @Synchronized
    fun loaded(model: String, backend: String) {
        if (isCpu(backend)) return
        store.put(RESIDENT, (resident() + pair(model, backend)).joinToString(","))
    }

    /** The GPU/NPU engine was closed by the app, not by a kill: it survived its work, so the strikes are cleared. */
    @Synchronized
    fun unloaded(model: String, backend: String) {
        if (isCpu(backend)) return
        val changes = HashMap<String, String?>()
        changes[RESIDENT] = (resident() - pair(model, backend)).joinToString(",").ifEmpty { null }
        record(model, backend)?.takeIf { it.state == State.PROVEN && it.strikes > 0 }?.let { changes[key(model, backend)] = it.copy(strikes = 0, at = now()).encode() }
        store.putAll(changes)
    }

    /**
     * Once per process start, before any model loads: forgets everything if the build changed, then turns the attempts left open and the GPU/NPU
     * engines that were resident in a process the system killed into strikes.
     */
    @Synchronized
    fun recover(lastExit: LastExit?): List<Event> {
        val events = ArrayList<Event>()
        if (store.get(BUILD) != build) {
            if (store.get(BUILD) != null) events += Event("backend_guard_reset_new_build", "*", "*", null)
            store.putAll(store.keys().associateWith<String, String?> { null } + (BUILD to build))
            return events
        }
        val exit = lastExit?.let { "${it.kind.name.lowercase()}: ${it.description}" } ?: "exit reason unknown"
        // The backend's own doing: the system killed it for memory, it crashed natively, or it hung.
        val backendsFault = lastExit != null && lastExit.kind in setOf(ExitKind.KILLED_FOR_MEMORY, ExitKind.NATIVE_CRASH, ExitKind.ANR)
        for ((p, phase) in pending()) {
            val (model, backend) = unpair(p) ?: continue
            when {
                lastExit?.kind == ExitKind.BENIGN -> events += Event("backend_attempt_interrupted", model, backend, record(model, backend), "phase=$phase; $exit; not counted")
                backendsFault -> {
                    val r = strike(model, backend, State.DIED, "process died while loading, in $phase ($exit)")
                    events += Event(if (r.state == State.BLOCKED) "backend_blocked" else "backend_died_while_loading", model, backend, r, "phase=$phase")
                    tooLarge(model, backend, r)?.let { events += it }
                }
                else -> {
                    val n = (store.get(INTERRUPTED + p)?.toIntOrNull() ?: 0) + 1
                    if (n >= INTERRUPTED_STRIKE) {
                        store.put(INTERRUPTED + p, null)
                        val r = strike(model, backend, State.DIED, "process ended $n times while loading, last in $phase ($exit)")
                        events += Event(if (r.state == State.BLOCKED) "backend_blocked" else "backend_died_while_loading", model, backend, r, "phase=$phase; repeated")
                    } else {
                        store.put(INTERRUPTED + p, n.toString())
                        events += Event("backend_attempt_interrupted", model, backend, record(model, backend), "phase=$phase; $exit; $n of $INTERRUPTED_STRIKE before it counts")
                    }
                }
            }
        }
        val killedInUse = lastExit != null &&
            (lastExit.kind == ExitKind.NATIVE_CRASH || lastExit.kind == ExitKind.ANR || (lastExit.kind == ExitKind.KILLED_FOR_MEMORY && lastExit.foreground))
        if (killedInUse) {
            resident().mapNotNull(::unpair).forEach { (model, backend) ->
                val r = strike(model, backend, State.DIED, "process died while loaded ($exit)")
                events += Event(if (r.state == State.BLOCKED) "backend_blocked" else "backend_died_while_loaded", model, backend, r)
            }
        }
        store.putAll(mapOf(PENDING to null, RESIDENT to null))
        return events
    }

    /** Every record, for the startup log. */
    @Synchronized
    fun all(): List<Event> = store.keys().filter { it.startsWith(PREFIX) }.mapNotNull { k ->
        val (model, backend) = unpair(k.removePrefix(PREFIX)) ?: return@mapNotNull null
        Event("backend_record", model, backend, Record.decode(store.get(k)))
    }

    /** The CPU is never blocked, so a model that keeps getting killed even there is too large for this phone: said once it is clear. */
    private fun tooLarge(model: String, backend: String, r: Record): Event? =
        if (isCpu(backend) && r.strikes >= BLOCK_UNPROVEN) Event("model_too_large_for_device", model, backend, r, "killed ${r.strikes} times on the CPU; a smaller model may be needed") else null

    private fun strike(model: String, backend: String, state: State, reason: String): Record {
        val r = record(model, backend)
        val strikes = (r?.strikes ?: 0) + 1
        val everProven = r?.everProven ?: false
        val limit = if (everProven) BLOCK_PROVEN else BLOCK_UNPROVEN
        val next = Record(if (!isCpu(backend) && strikes >= limit) State.BLOCKED else state, strikes, everProven, now(), reason)
        put(model, backend, next)
        return next
    }

    private fun put(model: String, backend: String, r: Record) = store.put(key(model, backend), r.encode())

    private fun resident(): Set<String> = store.get(RESIDENT)?.split(',')?.filter { unpair(it) != null }?.toSet() ?: emptySet()

    /** Open attempts and the step each is in: "model@GPU#initialize,other@CPU#start". An old single-entry value reads as phase "start". */
    private fun pending(): Map<String, String> = store.get(PENDING)?.split(',')?.mapNotNull { e ->
        val p = e.substringBefore('#')
        if (unpair(p) == null) null else p to e.substringAfter('#', "start").ifEmpty { "start" }
    }?.toMap() ?: emptyMap()

    private fun Map<String, String>.encodePending(): String? = if (isEmpty()) null else entries.joinToString(",") { (p, phase) -> "$p#$phase" }

    companion object {
        const val BLOCK_UNPROVEN = 2
        const val BLOCK_PROVEN = 3
        const val INFERENCE_STRIKE = 2
        const val INTERRUPTED_STRIKE = 2
        const val BLOCK_RETRY_MS = 7L * 24 * 60 * 60 * 1000

        /** [ModelView.selected] when the last load found no backend. */
        const val NONE = "NONE"
        private const val MAX_REASON = 300
        private const val PREFIX = "b:"
        private const val SKIP_PREFIX = "s:"
        private const val SELECTED = "sel:"
        private const val INFERENCE = "f:"
        private const val INTERRUPTED = "i:"
        private const val PENDING = "pending"
        private const val RESIDENT = "resident"
        private const val BUILD = "build"

        /** The fallback (the CPU) is never blocked and has nothing to unload from the system's memory. */
        fun isCpu(backend: String) = Accelerators.isFallback(backend)

        private fun pair(model: String, backend: String) = "$model@${backend.uppercase()}"
        private fun key(model: String, backend: String) = PREFIX + pair(model, backend)
        private fun unpair(s: String): Pair<String, String>? = s.lastIndexOf('@').takeIf { it > 0 && it < s.length - 1 }?.let { s.substring(0, it) to s.substring(it + 1) }
    }
}
