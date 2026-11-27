package com.irfinder.app

import android.hardware.ConsumerIrManager
import kotlin.math.abs

object Sender {

    /** Sends one code. Returns null on success, or a short error message. */
    fun send(ir: ConsumerIrManager, code: IrCode): String? {
        val f = IrCodec.encode(code)
        return sendRaw(ir, f.carrierHz, f.pattern)
    }

    fun sendRaw(ir: ConsumerIrManager, carrierHz: Int, pattern: IntArray): String? {
        return try {
            ir.transmit(nearestSupported(ir, carrierHz), pattern)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Some phones only accept certain carrier frequencies; use the closest one they offer. */
    private fun nearestSupported(ir: ConsumerIrManager, hz: Int): Int {
        val ranges = ir.carrierFrequencies
        if (ranges == null || ranges.isEmpty()) return hz
        for (r in ranges) {
            if (hz >= r.minFrequency && hz <= r.maxFrequency) return hz
        }
        var best = hz
        var bestDiff = Int.MAX_VALUE
        for (r in ranges) {
            for (edge in intArrayOf(r.minFrequency, r.maxFrequency)) {
                val d = abs(edge - hz)
                if (d < bestDiff) {
                    bestDiff = d
                    best = edge
                }
            }
        }
        return best
    }
}
