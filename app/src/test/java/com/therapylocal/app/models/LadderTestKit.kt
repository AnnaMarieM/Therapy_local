package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.ConcurrentHashMap

/** A [BackendGuard.Store] in memory, safe to share between threads, so a "new process" is a new guard on the same instance. */
internal class SharedStore : BackendGuard.Store {
    val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A resource an `open` builds: counted so a test can say nothing leaked. */
internal class Engine(val backend: String, private val counter: Counter) : AutoCloseable {
    var closed = false
    init {
        counter.opened++
    }
    override fun close() {
        check(!closed) { "closed twice" }
        closed = true
        counter.closed++
    }
}

internal class Counter {
    var opened = 0
    var closed = 0
    fun assertNoLeak(alive: Int = 0) = assertEquals("opened $opened, closed $closed", opened - alive, closed)
}

internal fun assertNoText(where: String, text: String, vararg secrets: String) = secrets.forEach {
    if (text.contains(it)) fail("$where quotes the message text '$it': $text")
}
