package com.therapylocal.app.engine.stress

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Percentiles of a list of millisecond samples (nearest rank). Empty list gives 0s. */
class Dist(samples: List<Long>) {
    private val s = samples.sorted()
    val count = s.size
    val max: Long = s.lastOrNull() ?: 0
    fun p(q: Int): Long = if (s.isEmpty()) 0 else s[((q / 100.0) * (s.size - 1)).toInt().coerceIn(0, s.size - 1)]
    val p50 get() = p(50)
    val p95 get() = p(95)
    val p99 get() = p(99)
    fun toJson(): JSONObject = JSONObject().put("p50", p50).put("p95", p95).put("p99", p99).put("max", max).put("n", count)
}

/**
 * Main-thread gaps and frame times during a scenario (U0: the worst main-thread gap must stay under 1000 ms x scale; p50/p95/p99 are data
 * only, because swiftshader jitter is not a product regression, anti-flake rule 7).
 *
 * Two measurements:
 *  - `gaps` (asserted): a ticker on the main looper every [tickMs]; the gap between two ticks. A main thread blocked for 2 s shows as one
 *    gap of about 2 s, also when the app has no window or is in the background. At [stop] the time since the last tick counts too, so a
 *    main thread that is still blocked is caught.
 *  - `frameTimes` (data): the [Choreographer] frame-callback gaps, recorded while the system delivers frames (none for a windowless app).
 *
 *     val frames = FrameProbe().also { it.start() }
 *     ... run the scenario ...
 *     val stats = frames.stop()
 *     stats.assertNoAnrClassGap(scale = LaneArgs.scale)          // max gap < 1000 ms x scale
 *     record.section("ui", JSONObject().put("frame_gap_ms", stats.gaps.toJson()))
 */
class FrameProbe(private val tickMs: Long = 16) {
    class Stats(val gaps: Dist, val frameTimes: Dist, val ticks: Int, val frames: Int) {
        val maxGapMs get() = gaps.max

        /** Fails when the worst gap is an ANR-class stall: [limitMs] x [scale]. */
        fun assertNoAnrClassGap(scale: Double = LaneArgs.scale, limitMs: Long = 1_000) {
            val limit = (limitMs * scale).toLong()
            if (gaps.max >= limit) throw AssertionError("main thread gap ${gaps.max} ms >= $limit ms (p50=${gaps.p50} p95=${gaps.p95} p99=${gaps.p99}, $ticks ticks, $frames frames)")
        }

        fun toJson(): JSONObject = JSONObject().put("frame_gap_ms", gaps.toJson()).put("frame_time_ms", frameTimes.toJson()).put("ticks", ticks)
    }

    private val main = Handler(Looper.getMainLooper())
    private val gaps = ArrayList<Long>()
    private val frameGaps = ArrayList<Long>()
    @Volatile private var running = false
    private var lastTick = 0L
    private var lastFrame = 0L
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            synchronized(gaps) {
                if (lastTick != 0L) gaps += (now - lastTick) / 1_000_000
                lastTick = now
                ticks++
            }
            main.postDelayed(this, tickMs)
        }
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            synchronized(gaps) {
                if (lastFrame != 0L) frameGaps += (frameTimeNanos - lastFrame) / 1_000_000
                lastFrame = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        synchronized(gaps) { gaps.clear(); frameGaps.clear(); ticks = 0; lastTick = System.nanoTime(); lastFrame = 0 }
        running = true
        main.post { ticker.run(); Choreographer.getInstance().postFrameCallback(callback) }
    }

    fun stop(): Stats {
        running = false
        main.removeCallbacks(ticker)
        synchronized(gaps) {
            if (lastTick != 0L) gaps += (System.nanoTime() - lastTick) / 1_000_000 // still blocked right now?
            return Stats(Dist(gaps.toList()), Dist(frameGaps.toList()), ticks, frameGaps.size)
        }
    }
}

/**
 * Main thread responsiveness (U0: ping latency < 500 ms x scale; K14 uses 1000 ms). A helper thread posts a no-op to the main looper every
 * [periodMs] and measures how long it waited. A ping that is still unanswered at [stop] counts with its age.
 *
 *     val dog = MainWatchdog().also { it.start() }
 *     ... scenario ...
 *     val w = dog.stop()
 *     w.assertResponsive(limitMs = 500)                          // x LaneArgs.scale
 *     record.section("ui", JSONObject().put("watchdog_max_ms", w.latency.max))
 */
class MainWatchdog(private val periodMs: Long = 100) {
    class Stats(val latency: Dist) {
        fun assertResponsive(limitMs: Long = 500, scale: Double = LaneArgs.scale) {
            val limit = (limitMs * scale).toLong()
            if (latency.max >= limit) throw AssertionError("main thread answered a ping after ${latency.max} ms >= $limit ms (p95=${latency.p95}, ${latency.count} pings)")
        }

        fun toJson(): JSONObject = JSONObject().put("watchdog_max_ms", latency.max).put("watchdog", latency.toJson())
    }

    private val main = Handler(Looper.getMainLooper())
    private val samples = ArrayList<Long>()
    @Volatile private var running = false
    @Volatile private var outstandingSince = 0L
    private var thread: HandlerThread? = null

    fun start() {
        synchronized(samples) { samples.clear() }
        running = true
        val t = HandlerThread("main-watchdog").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                if (outstandingSince == 0L) {
                    val sent = System.nanoTime()
                    outstandingSince = sent
                    main.post {
                        synchronized(samples) { samples += (System.nanoTime() - sent) / 1_000_000 }
                        outstandingSince = 0L
                    }
                }
                h.postDelayed(this, periodMs)
            }
        })
    }

    fun stop(): Stats {
        running = false
        val pending = outstandingSince.takeIf { it != 0L }?.let { (System.nanoTime() - it) / 1_000_000 }
        thread?.quitSafely()
        thread = null
        synchronized(samples) {
            if (pending != null) samples += pending
            return Stats(Dist(samples.toList()))
        }
    }
}

/**
 * Runs [block] on the main thread and waits (bounded) for it: the stimulus of the "main thread is busy" rows. Not a way to synchronise.
 */
fun runOnMain(timeoutMs: Long = 10_000, block: () -> Unit) {
    val done = CountDownLatch(1)
    var err: Throwable? = null
    Handler(Looper.getMainLooper()).post { try { block() } catch (t: Throwable) { err = t } finally { done.countDown() } }
    check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "main thread did not run the block in $timeoutMs ms" }
    err?.let { throw it }
}
