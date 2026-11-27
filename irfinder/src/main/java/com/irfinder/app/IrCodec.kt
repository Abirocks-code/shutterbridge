package com.irfinder.app

/*
 * Pure Kotlin (no Android classes) so it can be unit-tested on any machine.
 * Every encoder returns alternating MARK / SPACE durations in microseconds, starting with a MARK,
 * which is exactly what Android's ConsumerIrManager.transmit() expects.
 */

enum class Protocol(val label: String, val carrierHz: Int, val scannable: Boolean = true) {
    NEC("NEC", 38000),
    SAMSUNG("Samsung32", 38000),
    JVC("JVC", 38000),
    SONY12("Sony 12-bit", 40000),
    SONY15("Sony 15-bit", 40000),
    RC5("Philips RC5", 36000),
    NEC_EXT("NEC extended (16-bit address)", 38000, false)
}

data class IrCode(
    val protocol: Protocol,
    val address: Int,
    val command: Int,
    val toggle: Int = 0
) {
    fun describe(): String = "%s  addr 0x%02X  cmd 0x%02X".format(protocol.label, address, command)
}

object IrCodec {

    class Frame(val carrierHz: Int, val pattern: IntArray)

    fun encode(code: IrCode): Frame {
        val a = code.address
        val c = code.command and 0xFF
        val pattern = when (code.protocol) {
            // address, ~address, command, ~command
            Protocol.NEC -> necLike(
                9000, 4500, 560, 560, 1690,
                intArrayOf(a and 0xFF, a.inv() and 0xFF, c, c.inv() and 0xFF)
            )
            // 16-bit address (low byte first), command, ~command
            Protocol.NEC_EXT -> necLike(
                9000, 4500, 560, 560, 1690,
                intArrayOf(a and 0xFF, (a shr 8) and 0xFF, c, c.inv() and 0xFF)
            )
            // address, address, command, ~command
            Protocol.SAMSUNG -> necLike(
                4500, 4500, 560, 560, 1690,
                intArrayOf(a and 0xFF, a and 0xFF, c, c.inv() and 0xFF)
            )
            // address, command
            Protocol.JVC -> necLike(
                8400, 4200, 526, 526, 1574,
                intArrayOf(a and 0xFF, c)
            )
            Protocol.SONY12 -> sony(c and 0x7F, a and 0x1F, 5)
            Protocol.SONY15 -> sony(c and 0x7F, a and 0xFF, 8)
            Protocol.RC5 -> rc5(a and 0x1F, c and 0x3F, code.toggle)
        }
        return Frame(code.protocol.carrierHz, pattern)
    }

    /** Leader mark/space, then every byte least-significant bit first, then a stop mark. */
    private fun necLike(
        leaderMark: Int, leaderSpace: Int,
        bitMark: Int, zeroSpace: Int, oneSpace: Int,
        bytes: IntArray
    ): IntArray {
        val p = ArrayList<Int>(3 + bytes.size * 16)
        p.add(leaderMark)
        p.add(leaderSpace)
        for (b in bytes) {
            for (i in 0..7) {
                p.add(bitMark)
                p.add(if (((b shr i) and 1) == 1) oneSpace else zeroSpace)
            }
        }
        p.add(bitMark)
        return p.toIntArray()
    }

    /** Sony SIRC: 40 kHz, pulse-width coded, command (7 bits) then address, sent three times 45 ms apart. */
    private fun sony(cmd: Int, addr: Int, addrBits: Int): IntArray {
        val frame = ArrayList<Int>()
        frame.add(2400)
        frame.add(600)
        for (i in 0..6) {
            frame.add(if (((cmd shr i) and 1) == 1) 1200 else 600)
            frame.add(600)
        }
        for (i in 0 until addrBits) {
            frame.add(if (((addr shr i) and 1) == 1) 1200 else 600)
            frame.add(600)
        }
        var frameLen = 0
        for (v in frame) frameLen += v

        val out = ArrayList<Int>()
        for (n in 0 until 3) {
            out.addAll(frame)
            if (n < 2) {
                // pad the trailing space so frames start 45 ms apart
                out[out.size - 1] = out[out.size - 1] + (45000 - frameLen)
            }
        }
        out.removeAt(out.size - 1) // no trailing space needed
        return out.toIntArray()
    }

    /** Philips RC5: 36 kHz, Manchester coded, 889 us half-bits, 14 bits (S1 S2 T A4..A0 C5..C0). */
    private fun rc5(addr: Int, cmd: Int, toggle: Int): IntArray {
        val bits = ArrayList<Int>()
        bits.add(1)
        bits.add(1)
        bits.add(toggle and 1)
        for (i in 4 downTo 0) bits.add((addr shr i) and 1)
        for (i in 5 downTo 0) bits.add((cmd shr i) and 1)

        // 1 = carrier on (mark), 0 = off (space). Logical 1 = space then mark, logical 0 = mark then space.
        val levels = ArrayList<Int>()
        for (b in bits) {
            if (b == 1) {
                levels.add(0)
                levels.add(1)
            } else {
                levels.add(1)
                levels.add(0)
            }
        }

        // merge equal neighbours into runs of half-bits
        val runLevel = ArrayList<Int>()
        val runLen = ArrayList<Int>()
        for (l in levels) {
            if (runLevel.isNotEmpty() && runLevel[runLevel.size - 1] == l) {
                runLen[runLen.size - 1] = runLen[runLen.size - 1] + 1
            } else {
                runLevel.add(l)
                runLen.add(1)
            }
        }
        // must start with a mark and may end on a mark
        if (runLevel[0] == 0) {
            runLevel.removeAt(0)
            runLen.removeAt(0)
        }
        if (runLevel[runLevel.size - 1] == 0) {
            runLevel.removeAt(runLevel.size - 1)
            runLen.removeAt(runLen.size - 1)
        }
        val out = IntArray(runLen.size)
        for (i in runLen.indices) out[i] = runLen[i] * 889
        return out
    }
}
