package com.irfinder.app

/** A list of codes to try, in the order they should be tried. */
interface CodeSource {
    val key: String
    val title: String
    val size: Int
    fun codeAt(index: Int): IrCode
}

object ScanPlan {

    /** Addresses that cheap remotes use most often are tried first. */
    private val PRIORITY = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
        0xFF, 0x7F, 0xBF, 0xDF, 0xEF, 0xF7, 0xFB, 0xFD, 0xFE,
        0x03, 0x05, 0x06, 0x07, 0x0C, 0x18, 0x30, 0x60, 0xC0,
        0x1C, 0x3C, 0x78, 0xF0, 0x55, 0xAA
    )

    class Space(val maxAddr: Int, val maxCmd: Int)

    fun space(p: Protocol): Space = when (p) {
        Protocol.NEC, Protocol.SAMSUNG, Protocol.JVC, Protocol.NEC_EXT -> Space(255, 255)
        Protocol.SONY12 -> Space(31, 127)
        Protocol.SONY15 -> Space(255, 127)
        Protocol.RC5 -> Space(31, 63)
    }

    private val orders = HashMap<Protocol, IntArray>()

    fun addressOrder(p: Protocol): IntArray = orders.getOrPut(p) {
        val max = space(p).maxAddr
        val list = ArrayList<Int>()
        for (a in PRIORITY) if (a <= max && !list.contains(a)) list.add(a)
        for (a in 0..max) if (!list.contains(a)) list.add(a)
        list.toIntArray()
    }

    /** How many addresses the "quick" search covers. */
    fun quickCount(p: Protocol): Int = when (p) {
        Protocol.NEC, Protocol.SAMSUNG, Protocol.JVC, Protocol.NEC_EXT -> 16
        else -> 8
    }
}

/** Every command of the most likely addresses (quick) or of all addresses (full) for one protocol. */
class ProtocolSource(val protocol: Protocol, val quick: Boolean) : CodeSource {
    private val sp = ScanPlan.space(protocol)
    private val order = ScanPlan.addressOrder(protocol)
    private val addrCount = if (quick) minOf(ScanPlan.quickCount(protocol), order.size) else order.size
    private val cmdCount = sp.maxCmd + 1

    override val key: String = "proto_${protocol.name}_${if (quick) "q" else "f"}"
    override val title: String = "${protocol.label} - ${if (quick) "quick" else "full"} search"
    override val size: Int = addrCount * cmdCount

    override fun codeAt(index: Int): IrCode =
        IrCode(protocol, order[index / cmdCount], index % cmdCount, index and 1)
}

/** Every command for one known address (used to find the other buttons once one code is known). */
class AddressSource(val protocol: Protocol, val address: Int) : CodeSource {
    private val cmdCount = ScanPlan.space(protocol).maxCmd + 1

    override val key: String = "addr_${protocol.name}_$address"
    override val title: String = "${protocol.label} - address 0x%02X - every command".format(address)
    override val size: Int = cmdCount

    override fun codeAt(index: Int): IrCode = IrCode(protocol, address, index, index and 1)
}
