package com.chrono.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ResultPacketTest {
    private fun packet(): ByteArray {
        val buffer = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(65535.toShort()).putInt(125_000).putInt(1_700_000_000).put(1)
        buffer.putInt(100).putInt(2100).putShort(3990).putShort(0)
        buffer.putInt(-1).putInt(0).put(2).put(3).put(2).put(2)
        buffer.putShort(ResultPacket.crc16Ccitt(buffer.array(), 35).toShort())
        return buffer.array()
    }

    @Test fun validPacketPreservesUnsignedIdsAndTicks() {
        val result = ResultPacket.decode(packet())!!
        assertEquals(65535, result.id)
        assertEquals(0xFFFFFFFFL, result.bootId)
        assertEquals(125_000L, result.splitNs)
        assertTrue(result.crcValid)
    }

    @Test fun corruptionIsRejectedBeforeAcknowledgement() {
        val bytes = packet()
        bytes[5] = (bytes[5].toInt() xor 1).toByte()
        assertNull(ResultPacket.decode(bytes))
    }

    @Test fun truncatedModernPacketsAreNotAcceptedAsLegacy() {
        for (length in 12..36) assertNull("length=$length", ResultPacket.decode(packet().copyOf(length)))
        assertNull(ResultPacket.decode(ByteArray(10)))
    }

    @Test fun legacyElevenBytePacketStillDecodes() {
        assertEquals(125_000L, ResultPacket.decode(packet().copyOf(11))!!.splitNs)
    }

    @Test fun unsupportedVersionIsRejectedEvenWithValidCrc() {
        val bytes = packet()
        bytes[34] = 99
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(35, ResultPacket.crc16Ccitt(bytes, 35).toShort())
        assertNull(ResultPacket.decode(bytes))
    }
}
