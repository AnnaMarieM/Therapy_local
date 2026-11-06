package com.therapylocal.app.models

/**
 * What this phone has shown it can run, per model and backend, kept across restarts.
 *
 * Every load writes a note **before** it starts ([attempting]) and another once the backend passed its test reply ([proven]). A caught failure
 * is a strike ([failed]). A process that died with an attempt still open, or that the system killed in the foreground while a GPU/NPU engine
 * was loaded, gets a strike on the next start ([recover]): no try/catch sees the low-memory killer or a native crash, so this is the only way
 * not to walk into the same kill on every start.
 *
 * A backend that was never proven on this build is blocked after [BLOCK_UNPROVEN] strikes ("this device really cannot do this"); one that was
 * proven needs [BLOCK_PROVEN] strikes in a row, since a failure there is more likely transient (memory pressure). Strikes are cleared when the
 * app closes a GPU/NPU engine itself ([unloaded]): it loaded and survived its work. A load alone does not clear them, or a backend that loads
 * fine and is then killed mid-work would be retried forever. The CPU is never blocked: it is the last resort. An app or system update ([build]
 * changes) forgets everything, so a fixed driver gets its chance again.
 */
class BackendGuard(private val store: Store, private val build: String, private val now: () -> Long = System::currentTimeMillis) {
    /** Durable key-value storage: [put] must have reached the disk when it returns (the next thing may be a kill). A null value removes. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
        fun keys(): Set<String>
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
    data class Event(val evt: String, val model: String, val backend: String, val record: Record?)

    /** A backend not tried on the last load, and why (not enough free memory, missing library): not a strike, the device was never asked. */
    data class Skip(val at: Long, val reason: String)

    /** What one model ran on and what each backend has shown on this phone, for the settings screen. */
    data class ModelView(val model: String, val selected: String?, val selectedAt: Long?, val backends: Map<String, Pair<Record?, Skip?>>)

    /** The backend the last load of [model] ended on. */
    @Synchronized
    fun selected(model: String, backend: String) {
        store.put(SELECTED + model, "${backend.uppercase()}|${now()}")
        store.put(SKIP_PREFIX + pair(model, backend), null)
    }

    @Synchronized
    fun skipped(model: String, backend: String, reason: String) {
        store.put(SKIP_PREFIX + pair(model, backend), "${now()}|${reason.take(MAX_REASON)}")
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

    /** Forgets every record and block on this phone, so every backend is tried and proven again (a manual "test again"). */
    @Synchronized
    fun reset() {
        store.keys().filter { it != BUILD }.forEach { store.put(it, null) }
    }

    @Synchronized
    fun record(model: String, backend: String): Record? = Record.decode(store.get(key(model, backend)))

    @Synchronized
    fun blocked(model: String, backend: String): Boolean = record(model, backend)?.state == State.BLOCKED

    /** Written before the engine is created. */
    @Synchronized
    fun attempting(model: String, backend: String) {
        val r = record(model, backend)
        put(model, backend, Record(State.ATTEMPTING, r?.strikes ?: 0, r?.everProven ?: false, now(), "attempting"))
        store.put(PENDING, pair(model, backend))
    }

    /** Written once the backend loaded and answered its test. Strikes are kept until a clean [unloaded]: loading is not yet surviving the work. */
    @Synchronized
    fun proven(model: String, backend: String) {
        val r = record(model, backend)
        put(model, backend, Record(State.PROVEN, r?.strikes ?: 0, true, now(), "proven on this device"))
        store.put(PENDING, null)
    }

    /** A caught failure while loading. */
    @Synchronized
    fun failed(model: String, backend: String, reason: String?): Record {
        store.put(PENDING, null)
        return strike(model, backend, State.FAILED, reason ?: "error")
    }

    /**
     * A caught failure during inference on an engine that had loaded (and so was proven). Noted, not a strike: a bad prompt (too long, a schema
     * the sampler loops on) fails on every backend, and the job is retried anyway. Deaths are what block a backend.
     */
    @Synchronized
    fun inferenceFailed(model: String, backend: String, reason: String?): Record? {
        if (isCpu(backend)) return null
        val r = record(model, backend) ?: return null
        val next = r.copy(at = now(), reason = "proven on this device; last inference error: ${reason ?: "error"}")
        put(model, backend, next)
        return next
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
        store.put(RESIDENT, (resident() - pair(model, backend)).joinToString(",").ifEmpty { null })
        record(model, backend)?.takeIf { it.state == State.PROVEN && it.strikes > 0 }?.let { put(model, backend, it.copy(strikes = 0, at = now())) }
    }

    /**
     * Once per process start, before any model loads: forgets everything if the build changed, then turns an attempt left open and a GPU/NPU
     * engine that was resident in a killed foreground process into strikes.
     */
    @Synchronized
    fun recover(lastExit: LastExit?): List<Event> {
        val events = ArrayList<Event>()
        if (store.get(BUILD) != build) {
            if (store.get(BUILD) != null) events += Event("backend_guard_reset_new_build", "*", "*", null)
            store.keys().forEach { store.put(it, null) }
            store.put(BUILD, build)
            return events
        }
        val exit = lastExit?.let { "${it.kind.name.lowercase()}: ${it.description}" } ?: "unknown exit"
        store.get(PENDING)?.let(::unpair)?.let { (model, backend) ->
            if (lastExit?.kind != ExitKind.BENIGN) {
                val r = strike(model, backend, State.DIED, "process died while loading ($exit)")
                events += Event(if (r.state == State.BLOCKED) "backend_blocked" else "backend_died_while_loading", model, backend, r)
            }
        }
        val killedInUse = lastExit != null &&
            (lastExit.kind == ExitKind.NATIVE_CRASH || (lastExit.kind == ExitKind.KILLED_FOR_MEMORY && lastExit.foreground))
        if (killedInUse) {
            resident().mapNotNull(::unpair).forEach { (model, backend) ->
                val r = strike(model, backend, State.DIED, "process died while loaded ($exit)")
                events += Event(if (r.state == State.BLOCKED) "backend_blocked" else "backend_died_while_loaded", model, backend, r)
            }
        }
        store.put(PENDING, null)
        store.put(RESIDENT, null)
        return events
    }

    /** Every record, for the startup log. */
    @Synchronized
    fun all(): List<Event> = store.keys().filter { it.startsWith(PREFIX) }.mapNotNull { k ->
        val (model, backend) = unpair(k.removePrefix(PREFIX)) ?: return@mapNotNull null
        Event("backend_record", model, backend, Record.decode(store.get(k)))
    }

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

    private fun resident(): Set<String> = store.get(RESIDENT)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    companion object {
        const val BLOCK_UNPROVEN = 2
        const val BLOCK_PROVEN = 3
        private const val MAX_REASON = 300
        private const val PREFIX = "b:"
        private const val SKIP_PREFIX = "s:"
        private const val SELECTED = "sel:"
        private const val PENDING = "pending"
        private const val RESIDENT = "resident"
        private const val BUILD = "build"

        /** The fallback (the CPU) is never blocked and has nothing to unload from the system's memory. */
        fun isCpu(backend: String) = Accelerators.isFallback(backend)

        private fun pair(model: String, backend: String) = "$model@${backend.uppercase()}"
        private fun key(model: String, backend: String) = PREFIX + pair(model, backend)
        private fun unpair(s: String): Pair<String, String>? = s.lastIndexOf('@').takeIf { it > 0 }?.let { s.substring(0, it) to s.substring(it + 1) }
    }
}
