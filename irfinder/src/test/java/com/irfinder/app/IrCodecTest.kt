package com.irfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrCodecTest {

    /** Reads the data bytes (LSB first) out of a NEC-style pattern: leader, then mark/space pairs, then stop mark. */
    private fun bytesOf(p: IntArray, nBytes: Int): List<Int> {
        val out = ArrayList<Int>()
        for (b in 0 until nBytes) {
            var v = 0
            for (k in 0..7) {
                val space = p[3 + 2 * (b * 8 + k)]
                if (space > 1000) v = v or (1 shl k)
            }
            out.add(v)
        }
        return out
    }

    @Test
    fun necLgPowerVector() {
        // The well-known LG TV power code 0x20DF10EF is address 0x04, command 0x08.
        val f = IrCodec.encode(IrCode(Protocol.NEC, 0x04, 0x08))
        val p = f.pattern
        assertEquals(38000, f.carrierHz)
        assertEquals(67, p.size)
        assertEquals(9000, p[0])
        assertEquals(4500, p[1])
        assertEquals(560, p[p.size - 1])
        assertEquals(listOf(0x04, 0xFB, 0x08, 0xF7), bytesOf(p, 4))
    }

    @Test
    fun necExtendedUsesSixteenBitAddress() {
        val p = IrCodec.encode(IrCode(Protocol.NEC_EXT, 0x1234, 0x56)).pattern
        assertEquals(listOf(0x34, 0x12, 0x56, 0xA9), bytesOf(p, 4))
    }

    @Test
    fun samsungRepeatsAddress() {
        val p = IrCodec.encode(IrCode(Protocol.SAMSUNG, 0x07, 0x02)).pattern
        assertEquals(67, p.size)
        assertEquals(4500, p[0])
        assertEquals(listOf(0x07, 0x07, 0x02, 0xFD), bytesOf(p, 4))
    }

    @Test
    fun jvcIsSixteenBits() {
        val p = IrCodec.encode(IrCode(Protocol.JVC, 0x03, 0x5A)).pattern
        assertEquals(35, p.size)
        assertEquals(8400, p[0])
        assertEquals(listOf(0x03, 0x5A), bytesOf(p, 2))
    }

    @Test
    fun sony12FramesAre45msApart() {
        // Sony TV power: address 1, command 21.
        val f = IrCodec.encode(IrCode(Protocol.SONY12, 1, 21))
        val p = f.pattern
        assertEquals(40000, f.carrierHz)
        assertEquals(26 * 3 - 1, p.size)
        var sum = 0
        for (i in 0 until 26) sum += p[i]
        assertEquals(45000, sum)
        var cmd = 0
        var addr = 0
        for (k in 0 until 12) {
            val bit = if (p[2 + 2 * k] > 900) 1 else 0
            if (k < 7) cmd = cmd or (bit shl k) else addr = addr or (bit shl (k - 7))
        }
        assertEquals(21, cmd)
        assertEquals(1, addr)
    }

    @Test
    fun rc5RoundTrip() {
        val f = IrCodec.encode(IrCode(Protocol.RC5, 5, 13, 0))
        assertEquals(36000, f.carrierHz)
        val levels = ArrayList<Int>()
        levels.add(0) // the idle half-bit before the first mark is not transmitted
        var level = 1
        for (d in f.pattern) {
            val n = Math.round(d / 889.0).toInt()
            for (i in 0 until n) levels.add(level)
            level = 1 - level
        }
        assertEquals(28, levels.size)
        val bits = ArrayList<Int>()
        for (i in 0 until 14) {
            val a = levels[2 * i]
            val b = levels[2 * i + 1]
            bits.add(if (a == 0 && b == 1) 1 else 0)
        }
        assertEquals(listOf(1, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 1), bits)
    }

    @Test
    fun scanSizes() {
        assertEquals(16 * 256, ProtocolSource(Protocol.NEC, true).size)
        assertEquals(65536, ProtocolSource(Protocol.NEC, false).size)
        assertEquals(32 * 128, ProtocolSource(Protocol.SONY12, false).size)
        assertEquals(8 * 64, ProtocolSource(Protocol.RC5, true).size)
        assertEquals(256, AddressSource(Protocol.NEC, 0x00).size)
    }

    @Test
    fun scanOrderStartsWithAddressZeroAndCoversEverything() {
        val order = ScanPlan.addressOrder(Protocol.NEC)
        assertEquals(256, order.size)
        assertEquals(256, order.toSet().size)
        assertEquals(0x00, order[0])

        val src = ProtocolSource(Protocol.NEC, false)
        assertEquals(IrCode(Protocol.NEC, 0x00, 0x00, 0), src.codeAt(0))
        assertEquals(0x00, src.codeAt(255).address)
        assertEquals(0xFF, src.codeAt(255).command)
        assertEquals(0x01, src.codeAt(256).address)

        val seen = HashSet<Pair<Int, Int>>()
        for (i in 0 until src.size) {
            val c = src.codeAt(i)
            seen.add(Pair(c.address, c.command))
        }
        assertEquals(65536, seen.size)
    }

    @Test
    fun everyScannableCodeProducesAValidPattern() {
        for (p in Protocol.values()) {
            if (!p.scannable) continue
            val src = ProtocolSource(p, false)
            var i = 0
            while (i < src.size) {
                val f = IrCodec.encode(src.codeAt(i))
                assertTrue(f.pattern.isNotEmpty())
                for (d in f.pattern) assertTrue("bad duration $d for ${p.name}", d in 1..200000)
                i += 37
            }
        }
    }
}
