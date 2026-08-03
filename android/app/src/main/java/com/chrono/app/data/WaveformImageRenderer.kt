package com.chrono.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs

/** Produces the portable, last-saved waveform image stored with each real log. */
object WaveformImageRenderer {
    private const val WIDTH = 1600
    private const val HEIGHT = 900

    fun renderPng(result: TestResult, accuracyEnvelopePercent: Double): ByteArray {
        val events = result.waveformEvents()
        if (events.isEmpty()) return ByteArray(0)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 24, 31))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 239, 245)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val dim = Paint(text).apply { color = Color.rgb(164, 174, 188) }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(59, 66, 78); strokeWidth = 2f
        }
        val amber = Color.rgb(255, 181, 48)
        val teal = Color.rgb(52, 205, 196)

        text.textSize = 42f
        canvas.drawText(result.label.ifBlank { "Chronograph waveform" }, 70f, 66f, text)
        dim.textSize = 24f
        canvas.drawText("Saved waveform review", 72f, 102f, dim)

        val left = 150f
        val right = 1530f
        val top = 155f
        val mid = 430f
        val bottom = 705f
        val minTick = 0L
        val autoStart = automaticOffset(result.rawStartTicks, result.traceBaseTicks)
        val autoStop = automaticOffset(result.rawStopTicks, result.traceBaseTicks)
        val start = result.reviewedStartOffsetTicks ?: autoStart
        val stop = result.reviewedStopOffsetTicks ?: autoStop
        val maxTick = maxOf(
            events.maxOf { it.offsetTicks }, start, stop, 1L,
        )
        val padding = (maxTick / 30L).coerceAtLeast(16L)
        val viewEnd = maxTick + padding
        fun x(tick: Long): Float = left +
            ((tick - minTick).toDouble() / (viewEnd - minTick).toDouble() * (right - left)).toFloat()

        repeat(6) { i ->
            val gx = left + (right - left) * i / 5f
            canvas.drawLine(gx, top, gx, bottom, grid)
            dim.textSize = 19f
            val tick = ((viewEnd - minTick) * i / 5L) + minTick
            canvas.drawText(formatTime(ticksToNanoseconds(tick)), gx - 25f, bottom + 33f, dim)
        }
        canvas.drawLine(left, mid, right, mid, grid)
        canvas.drawLine(left, bottom, right, bottom, grid)

        text.textSize = 25f
        text.color = amber
        canvas.drawText("CH1  START", 22f, 285f, text)
        text.color = teal
        canvas.drawText("CH2  STOP", 22f, 560f, text)
        drawChannel(canvas, events, 0, minTick, viewEnd, left, right, top, mid, amber)
        drawChannel(canvas, events, 1, minTick, viewEnd, left, right, mid, bottom, teal)

        drawSelection(canvas, start, 0, events, x(start), top, bottom, amber, "START", text)
        drawSelection(canvas, stop, 1, events, x(stop), top, bottom, teal, "STOP", text)

        val selectedNs = ticksToNanoseconds(stop - start)
        val startEdge = events.firstOrNull { it.channel == 0 && it.offsetTicks == start }
        val stopEdge = events.firstOrNull { it.channel == 1 && it.offsetTicks == stop }
        val mode = if (result.isWaveformReviewed) "USER SELECTED" else "AUTOMATIC"
        text.color = Color.rgb(235, 239, 245)
        text.textSize = 28f
        canvas.drawText(
            "$mode: START ${edgeName(startEdge)} at ${formatTime(ticksToNanoseconds(start))}    " +
                "STOP ${edgeName(stopEdge)} at ${formatTime(ticksToNanoseconds(stop))}",
            70f, 790f, text,
        )
        text.textSize = 32f
        canvas.drawText(
            "Delta ${formatTime(selectedNs)}    %.2f m/s    %.1f ft/s"
                .format(Locale.US, result.metersPerSecond, result.feetPerSecond),
            70f, 836f, text,
        )
        dim.textSize = 22f
        val velocityError = abs(result.feetPerSecond) * accuracyEnvelopePercent / 100.0
        canvas.drawText(
            "Distance %.4f m    Estimated error +/- %.2f%% GAE (+/- %.1f ft/s)"
                .format(Locale.US, result.distanceM, accuracyEnvelopePercent, velocityError),
            72f, 872f, dim,
        )

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun drawChannel(
        canvas: Canvas,
        events: List<WaveformEvent>,
        channel: Int,
        viewStart: Long,
        viewEnd: Long,
        left: Float,
        right: Float,
        bandTop: Float,
        bandBottom: Float,
        color: Int,
    ) {
        val relevant = events.filter { it.channel == channel }
        var high = relevant.lastOrNull { it.offsetTicks < viewStart }?.high ?: false
        val highY = bandTop + (bandBottom - bandTop) * .28f
        val lowY = bandTop + (bandBottom - bandTop) * .72f
        fun y() = if (high) highY else lowY
        fun x(tick: Long) = left +
            ((tick - viewStart).toDouble() / (viewEnd - viewStart).coerceAtLeast(1L) * (right - left)).toFloat()
        val path = Path().apply { moveTo(left, y()) }
        relevant.filter { it.offsetTicks in viewStart..viewEnd }.forEach { event ->
            val ex = x(event.offsetTicks)
            path.lineTo(ex, y())
            high = event.high
            path.lineTo(ex, y())
        }
        path.lineTo(right, y())
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 5f; style = Paint.Style.STROKE
        })
    }

    private fun drawSelection(
        canvas: Canvas,
        tick: Long,
        channel: Int,
        events: List<WaveformEvent>,
        x: Float,
        top: Float,
        bottom: Float,
        color: Int,
        label: String,
        text: Paint,
    ) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 4f }
        canvas.drawLine(x, top, x, bottom, line)
        val event = events.firstOrNull { it.channel == channel && it.offsetTicks == tick }
        text.color = color
        text.textSize = 20f
        canvas.drawText("$label ${edgeName(event)}", (x + 8f).coerceAtMost(1390f), top + 26f, text)
    }

    private fun automaticOffset(absoluteTicks: Long, baseTicks: Long): Long =
        (absoluteTicks - baseTicks) and 0xFFFFFFFFL

    private fun edgeName(event: WaveformEvent?): String = when (event?.high) {
        true -> "RISING"
        false -> "FALLING"
        null -> "EDGE"
    }

    private fun formatTime(ns: Long): String {
        val sign = if (ns < 0) "-" else ""
        val value = abs(ns)
        return when {
            value < 1_000 -> "$sign${value} ns"
            value < 1_000_000 -> "$sign%.3f us".format(Locale.US, value / 1_000.0)
            value < 1_000_000_000 -> "$sign%.3f ms".format(Locale.US, value / 1_000_000.0)
            else -> "$sign%.3f s".format(Locale.US, value / 1_000_000_000.0)
        }
    }
}
