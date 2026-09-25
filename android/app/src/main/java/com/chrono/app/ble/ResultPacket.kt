package com.chrono.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reject corrupt or truncated packets before they can reach storage/ACK logic. */
internal object ResultPacket {
    fun decode(v: ByteArray): RawResult? {
        if (v.size != 11 && v.size != 37) return null
        val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.short.toInt() and 0xFFFF
        val splitNs = b.int.toLong() and 0xFFFFFFFFL
        val epochSec = b.int.toLong() and 0xFFFFFFFFL
        val flags = b.get().toInt() and 0xFF
        if (v.size >= 37) {
            val startTicks = b.int.toLong() and 0xFFFFFFFFL
            val stopTicks = b.int.toLong() and 0xFFFFFFFFL
            val batteryMv = (b.short.toInt() and 0xFFFF).takeUnless { it == 0xFFFF }
            val portFlags = b.short.toInt() and 0xFFFF
            val bootId = b.int.toLong() and 0xFFFFFFFFL
            val resetCause = b.int.toLong() and 0xFFFFFFFFL
            val hwRev = b.get().toInt() and 0xFF
            val fwMajor = b.get().toInt() and 0xFF
            val fwMinor = b.get().toInt() and 0xFF
            val formatVersion = b.get().toInt() and 0xFF
            val packetCrc = b.short.toInt() and 0xFFFF
            if (formatVersion != 2 || packetCrc != crc16Ccitt(v, 35)) return null
            return RawResult(id, splitNs, epochSec, flags, startTicks, stopTicks,
                    batteryMv, portFlags, bootId, resetCause, hwRev, fwMajor,
                    fwMinor, formatVersion, true)
        } else {
            return RawResult(id, splitNs, epochSec, flags)
        }
    }

    fun crc16Ccitt(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (index in 0 until length.coerceAtMost(data.size)) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

}
