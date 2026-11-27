package com.irfinder.app

import android.hardware.ConsumerIrManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Sends codes one after another at a fixed pace in a background thread and remembers the most
 * recent ones, so that when you say "the fan reacted" we can work out which codes were sent
 * around that moment.
 */
class Scanner {

    interface Listener {
        fun onProgress(code: IrCode, done: Int, total: Int, title: String)
        fun onFinished()
        fun onError(message: String)
    }

    private class Sent(val code: IrCode, val at: Long)

    private val lock = Any()
    private val history = ArrayList<Sent>()
    private val positions = ConcurrentHashMap<String, Int>()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running

    fun start(
        ir: ConsumerIrManager,
        sources: List<CodeSource>,
        starts: IntArray,
        perSec: Int,
        listener: Listener
    ) {
        stop()
        synchronized(lock) { history.clear() }
        running = true
        val interval = 1000L / perSec

        val t = Thread {
            try {
                for ((n, src) in sources.withIndex()) {
                    var i = starts[n]
                    positions[src.key] = i
                    while (i < src.size) {
                        if (!running) return@Thread
                        val code = src.codeAt(i)
                        val t0 = SystemClock.elapsedRealtime()
                        val err = Sender.send(ir, code)
                        if (err != null) {
                            running = false
                            listener.onError(err)
                            return@Thread
                        }
                        synchronized(lock) {
                            history.add(Sent(code, t0))
                            if (history.size > 120) history.removeAt(0)
                        }
                        i++
                        positions[src.key] = i
                        listener.onProgress(code, i, src.size, src.title)
                        val wait = interval - (SystemClock.elapsedRealtime() - t0)
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
                running = false
                listener.onFinished()
            } catch (e: InterruptedException) {
                // stopped on purpose
            }
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null) {
            t.interrupt()
            try {
                t.join(800)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
    }

    fun positionsSnapshot(): Map<String, Int> = HashMap(positions)

    /**
     * Codes sent in the last few seconds, most likely first. People tap about a second after they
     * see the fan react, so codes sent roughly 1.3 s ago come first.
     */
    fun candidates(): List<IrCode> {
        val now = SystemClock.elapsedRealtime()
        val list = synchronized(lock) { history.toList() }
        return list
            .filter { now - it.at <= 7000 }
            .sortedBy { abs((now - it.at) - 1300) }
            .map { it.code }
            .distinct()
            .take(60)
    }
}
