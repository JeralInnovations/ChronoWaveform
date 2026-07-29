package com.chrono.app.data

import android.util.Base64
import com.chrono.app.ble.TraceEdge

const val WAVEFORM_TIMER_HZ = 16_000_000L
const val TRACE_OVERFLOW = 1
const val TRACE_EDGE_LOSS_SUSPECTED = 2
const val TRACE_STOP_ACTIVITY_BEFORE_START = 4

data class WaveformEvent(
    val offsetTicks: Long,
    val channel: Int,
    val high: Boolean,
)

object WaveformCodec {
    fun encode(events: List<TraceEdge>): String {
        if (events.isEmpty()) return ""
        val bytes = ByteArray(events.size * 4)
        events.forEachIndexed { index, event ->
            val offset = index * 4
            val ticks = event.offsetTicks.coerceIn(0, 0xFFFFFF)
            bytes[offset] = (ticks and 0xFF).toByte()
            bytes[offset + 1] = ((ticks shr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((ticks shr 16) and 0xFF).toByte()
            bytes[offset + 3] =
                ((event.channel and 1) or if (event.high) 2 else 0).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decode(encoded: String): List<WaveformEvent> {
        if (encoded.isBlank()) return emptyList()
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { return emptyList() }
        if (bytes.size % 4 != 0) return emptyList()
        return List(bytes.size / 4) { index ->
            val offset = index * 4
            val ticks = (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16)
            val meta = bytes[offset + 3].toInt() and 0xFF
            WaveformEvent(ticks, meta and 1, meta and 2 != 0)
        }
    }
}

fun ticksToNanoseconds(ticks: Long): Long =
    ticks * 1_000_000_000L / WAVEFORM_TIMER_HZ
