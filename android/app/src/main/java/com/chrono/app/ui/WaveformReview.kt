package com.chrono.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import com.chrono.app.data.TRACE_EDGE_LOSS_SUSPECTED
import com.chrono.app.data.TRACE_OVERFLOW
import com.chrono.app.data.TRACE_STOP_ACTIVITY_BEFORE_START
import com.chrono.app.data.TestResult
import com.chrono.app.data.WaveformEvent
import com.chrono.app.data.ticksToNanoseconds
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Teal
import com.chrono.app.ui.theme.TextDim
import kotlin.math.abs
import kotlin.math.max

/**
 * Fitted, read-only waveform used by the automatic shot-return dialog. Tapping
 * anywhere opens the full-screen point selector.
 */
@Composable
fun WaveformPreview(
    result: TestResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = remember(result.traceData) { result.waveformEvents() }
    if (events.isEmpty()) return
    val maxTick = max(1L, events.maxOfOrNull { it.offsetTicks } ?: 1L)
    val automaticStart = automaticOffset(result.rawStartTicks, result.traceBaseTicks)
        .takeIf { it in 0..maxTick }
        ?: events.firstOrNull { it.channel == 0 && it.high }?.offsetTicks
        ?: 0L
    val automaticStop = automaticOffset(result.rawStopTicks, result.traceBaseTicks)
        .takeIf { it in 0..maxTick }
        ?: events.firstOrNull { it.channel == 1 && it.high }?.offsetTicks
        ?: maxTick
    val selectedStart = result.reviewedStartOffsetTicks ?: automaticStart
    val selectedStop = result.reviewedStopOffsetTicks ?: automaticStop

    WaveformChart(
        events = events,
        viewStart = 0L,
        viewEnd = maxTick,
        automaticStart = automaticStart,
        automaticStop = automaticStop,
        selectedStart = selectedStart,
        selectedStop = selectedStop,
        selectedStartHigh = edgeHighAt(events, 0, selectedStart) ?: true,
        selectedStopHigh = edgeHighAt(events, 1, selectedStop) ?: true,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
fun WaveformReviewDialog(
    result: TestResult,
    accuracyEnvelopePercentForSplit: (Long) -> Double,
    onDismiss: () -> Unit,
    onApply: (startOffsetTicks: Long, stopOffsetTicks: Long) -> Unit,
    onReset: () -> Unit,
) {
    val events = remember(result.traceData) { result.waveformEvents() }
    val maxTick = max(1L, events.maxOfOrNull { it.offsetTicks } ?: 1L)
    val automaticStart = remember(result.rawStartTicks, result.traceBaseTicks, events) {
        automaticOffset(result.rawStartTicks, result.traceBaseTicks)
            .takeIf { it in 0..maxTick }
            ?: events.firstOrNull { it.channel == 0 && it.high }?.offsetTicks
            ?: 0L
    }
    val automaticStop = remember(result.rawStopTicks, result.traceBaseTicks, events) {
        automaticOffset(result.rawStopTicks, result.traceBaseTicks)
            .takeIf { it in 0..maxTick }
            ?: events.firstOrNull { it.channel == 1 && it.high }?.offsetTicks
            ?: maxTick
    }
    var startTick by remember(result.uid, result.reviewedStartOffsetTicks) {
        mutableLongStateOf(result.reviewedStartOffsetTicks ?: automaticStart)
    }
    var stopTick by remember(result.uid, result.reviewedStopOffsetTicks) {
        mutableLongStateOf(result.reviewedStopOffsetTicks ?: automaticStop)
    }
    var startHigh by remember(result.uid, result.reviewedStartOffsetTicks, result.traceData) {
        mutableStateOf(
            edgeHighAt(events, 0, result.reviewedStartOffsetTicks ?: automaticStart) ?: true
        )
    }
    var stopHigh by remember(result.uid, result.reviewedStopOffsetTicks, result.traceData) {
        mutableStateOf(
            edgeHighAt(events, 1, result.reviewedStopOffsetTicks ?: automaticStop) ?: true
        )
    }
    var viewStart by remember(result.uid) { mutableLongStateOf(0L) }
    var viewEnd by remember(result.uid) { mutableLongStateOf(maxTick) }
    var chartWidth by remember { mutableStateOf(1) }

    fun fitAll() {
        viewStart = 0
        viewEnd = maxTick
    }

    fun zoom(factor: Double, panPixels: Float = 0f) {
        val span = (viewEnd - viewStart).coerceAtLeast(1L)
        val newSpan = (span / factor).toLong().coerceIn(8L, maxTick.coerceAtLeast(8L))
        val center = (viewStart + viewEnd) / 2
        val panTicks = (panPixels / chartWidth.toDouble() * span).toLong()
        var nextStart = center - newSpan / 2 - panTicks
        nextStart = nextStart.coerceIn(0L, (maxTick - newSpan).coerceAtLeast(0L))
        viewStart = nextStart
        viewEnd = (nextStart + newSpan).coerceAtMost(maxTick)
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom(zoomChange.toDouble(), panChange.x)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Waveform review", style = MaterialTheme.typography.titleLarge)
                        Text(
                            result.label.ifBlank { "Recorded shot" },
                            color = TextDim,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close waveform review")
                    }
                }

                if (events.isEmpty()) {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Text(
                            "The result was received, but it contains no waveform transitions.",
                            modifier = Modifier.padding(18.dp),
                            color = Bad,
                        )
                    }
                } else {
                    Text(
                        "Choose Rising or Falling for each cursor, then tap an amber CH1 " +
                            "edge for START and a teal CH2 edge for STOP.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    WaveformChart(
                        events = events,
                        viewStart = viewStart,
                        viewEnd = viewEnd,
                        automaticStart = automaticStart,
                        automaticStop = automaticStop,
                        selectedStart = startTick,
                        selectedStop = stopTick,
                        selectedStartHigh = startHigh,
                        selectedStopHigh = stopHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                            .padding(horizontal = 8.dp)
                            .onSizeChanged { chartWidth = it.width.coerceAtLeast(1) }
                            .transformable(transformState)
                            .pointerInput(events, viewStart, viewEnd) {
                                detectTapGestures { position ->
                                    val channel = if (position.y < size.height / 2f) 0 else 1
                                    val tick = viewStart +
                                        ((position.x / size.width) * (viewEnd - viewStart)).toLong()
                                    val high = if (channel == 0) startHigh else stopHigh
                                    nearestEdge(events, channel, tick, high)?.let {
                                        if (channel == 0) startTick = it else stopTick = it
                                    }
                                }
                            },
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = ::fitAll, modifier = Modifier.weight(1f)) {
                            Text("Fit")
                        }
                        OutlinedButton(onClick = { zoom(1.8) }, modifier = Modifier.weight(1f)) {
                            Text("Zoom +")
                        }
                        OutlinedButton(onClick = { zoom(1 / 1.8) }, modifier = Modifier.weight(1f)) {
                            Text("Zoom -")
                        }
                    }

                    CursorControls(
                        label = "START · CH1",
                        tick = startTick,
                        color = Amber,
                        edgeHigh = startHigh,
                        onEdgeHighChange = { high ->
                            nearestEdge(events, 0, startTick, high)?.let {
                                startHigh = high
                                startTick = it
                            }
                        },
                        onPrevious = {
                            previousEdge(events, 0, startTick, startHigh)?.let { startTick = it }
                        },
                        onNext = {
                            nextEdge(events, 0, startTick, startHigh)?.let { startTick = it }
                        },
                    )
                    CursorControls(
                        label = "STOP · CH2",
                        tick = stopTick,
                        color = Teal,
                        edgeHigh = stopHigh,
                        onEdgeHighChange = { high ->
                            nearestEdge(events, 1, stopTick, high)?.let {
                                stopHigh = high
                                stopTick = it
                            }
                        },
                        onPrevious = {
                            previousEdge(events, 1, stopTick, stopHigh)?.let { stopTick = it }
                        },
                        onNext = {
                            nextEdge(events, 1, stopTick, stopHigh)?.let { stopTick = it }
                        },
                    )

                    MeasurementCard(
                        result = result,
                        startTick = startTick,
                        stopTick = stopTick,
                        accuracyEnvelopePercent = accuracyEnvelopePercentForSplit(
                            ticksToNanoseconds(stopTick - startTick),
                        ),
                    )
                    TraceWarnings(result.traceFlags)
                }

                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                startTick = automaticStart
                                stopTick = automaticStop
                                startHigh = true
                                stopHigh = true
                                onReset()
                            },
                        ) { Text("Automatic") }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = events.isNotEmpty(),
                            onClick = { onApply(startTick, stopTick) },
                        ) { Text("Apply") }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun CursorControls(
    label: String,
    tick: Long,
    color: Color,
    edgeHigh: Boolean,
    onEdgeHighChange: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatTraceTime(ticksToNanoseconds(tick)),
                        fontFamily = FontFamily.Monospace,
                        color = color,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.NavigateBefore, "Previous selected edge")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.NavigateNext, "Next selected edge")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = edgeHigh,
                    onClick = { onEdgeHighChange(true) },
                    label = { Text("Rising") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = !edgeHigh,
                    onClick = { onEdgeHighChange(false) },
                    label = { Text("Falling") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MeasurementCard(
    result: TestResult,
    startTick: Long,
    stopTick: Long,
    accuracyEnvelopePercent: Double,
) {
    val deltaNs = ticksToNanoseconds(stopTick - startTick)
    val velocityMps = if (deltaNs != 0L && result.distanceM > 0) {
        result.distanceM / (deltaNs / 1_000_000_000.0)
    } else 0.0
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("CURSOR MEASUREMENT", style = MaterialTheme.typography.labelSmall, color = TextDim)
            Spacer(Modifier.height(6.dp))
            Text(
                "Δt  ${formatTraceTime(deltaNs)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                color = Amber,
            )
            Text(
                "Ticks  ${stopTick - startTick}    Distance  %.3f in".format(result.distanceM * 39.3701),
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (velocityMps == 0.0) "Velocity unavailable"
                else "Velocity  %.2f m/s  ·  %.1f ft/s".format(velocityMps, velocityMps * 3.28084),
                style = MaterialTheme.typography.titleMedium,
            )
            if (velocityMps != 0.0) {
                val velocityErrorFps =
                    kotlin.math.abs(velocityMps * 3.28084) * accuracyEnvelopePercent / 100.0
                Text(
                    "Estimated error  +/- %.1f%% GAE  (+/- %.1f ft/s)"
                        .format(accuracyEnvelopePercent, velocityErrorFps),
                    color = Teal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val autoDifference = deltaNs - result.splitNs
            Text(
                "Automatic ${formatTraceTime(result.splitNs)}  ·  change ${formatSignedTime(autoDifference)}",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TraceWarnings(flags: Int) {
    val warnings = buildList {
        if (flags and TRACE_OVERFLOW != 0) add("The device trace buffer filled; later edges may be missing.")
        if (flags and TRACE_EDGE_LOSS_SUSPECTED != 0) add("At least one transition may have occurred before firmware copied the previous edge.")
        if (flags and TRACE_STOP_ACTIVITY_BEFORE_START != 0) add("CH2 activity was observed before the first CH1 edge.")
    }
    if (warnings.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Bad.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("TRACE QUALITY", color = Bad, style = MaterialTheme.typography.labelMedium)
            warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun WaveformChart(
    events: List<WaveformEvent>,
    viewStart: Long,
    viewEnd: Long,
    automaticStart: Long,
    automaticStop: Long,
    selectedStart: Long,
    selectedStop: Long,
    selectedStartHigh: Boolean,
    selectedStopHigh: Boolean,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.background(surface, RoundedCornerShape(12.dp))) {
        val left = 42.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val top = 24.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val plotWidth = (right - left).coerceAtLeast(1f)
        val span = (viewEnd - viewStart).coerceAtLeast(1L)
        fun xFor(tick: Long): Float =
            left + ((tick - viewStart).toDouble() / span.toDouble() * plotWidth).toFloat()

        repeat(6) { index ->
            val x = left + plotWidth * index / 5f
            drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
        }
        drawLine(grid, Offset(left, size.height / 2f), Offset(right, size.height / 2f))

        drawDigitalChannel(events, 0, viewStart, viewEnd, left, plotWidth, top, size.height / 2f, Amber)
        drawDigitalChannel(events, 1, viewStart, viewEnd, left, plotWidth, size.height / 2f, bottom, Teal)

        fun pointY(channel: Int, high: Boolean): Float {
            val bandTop = if (channel == 0) top else size.height / 2f
            val bandBottom = if (channel == 0) size.height / 2f else bottom
            return bandTop + (bandBottom - bandTop) * if (high) 0.28f else 0.72f
        }

        // Every rising and falling edge is selectable. Vertical position shows
        // which side of the pulse the edge enters.
        events.asSequence()
            .filter { it.offsetTicks in viewStart..viewEnd }
            .forEach { event ->
                val color = if (event.channel == 0) Amber else Teal
                drawCircle(
                    color = color.copy(alpha = 0.65f),
                    radius = 4.dp.toPx(),
                    center = Offset(xFor(event.offsetTicks), pointY(event.channel, event.high)),
                )
            }

        if (automaticStart in viewStart..viewEnd) {
            drawLine(Amber.copy(alpha = 0.35f), Offset(xFor(automaticStart), top), Offset(xFor(automaticStart), bottom), 2f)
        }
        if (automaticStop in viewStart..viewEnd) {
            drawLine(Teal.copy(alpha = 0.35f), Offset(xFor(automaticStop), top), Offset(xFor(automaticStop), bottom), 2f)
        }
        if (selectedStart in viewStart..viewEnd) {
            drawLine(Amber, Offset(xFor(selectedStart), top), Offset(xFor(selectedStart), bottom), 3f)
        }
        if (selectedStop in viewStart..viewEnd) {
            drawLine(Teal, Offset(xFor(selectedStop), top), Offset(xFor(selectedStop), bottom), 3f)
        }
        if (selectedStart in viewStart..viewEnd) {
            val center = Offset(xFor(selectedStart), pointY(0, selectedStartHigh))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = center)
            drawCircle(Amber, radius = 6.dp.toPx(), center = center)
        }
        if (selectedStop in viewStart..viewEnd) {
            val center = Offset(xFor(selectedStop), pointY(1, selectedStopHigh))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = center)
            drawCircle(Teal, radius = 6.dp.toPx(), center = center)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                230,
                (textColor.red * 255).toInt(),
                (textColor.green * 255).toInt(),
                (textColor.blue * 255).toInt(),
            )
            textSize = 12.sp.toPx()
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("CH1  START", 8.dp.toPx(), top + 14.dp.toPx(), paint)
            drawText("CH2  STOP", 8.dp.toPx(), size.height / 2f + 18.dp.toPx(), paint)
            drawText(
                formatTraceTime(ticksToNanoseconds(viewStart)),
                left,
                size.height - 8.dp.toPx(),
                paint,
            )
            val endLabel = formatTraceTime(ticksToNanoseconds(viewEnd))
            drawText(endLabel, right - paint.measureText(endLabel), size.height - 8.dp.toPx(), paint)
            if (selectedStart in viewStart..viewEnd) {
                drawText(
                    "A ${if (selectedStartHigh) "RISE" else "FALL"}  " +
                        formatTraceTime(ticksToNanoseconds(selectedStart)),
                    (xFor(selectedStart) + 5.dp.toPx()).coerceAtMost(right - 110.dp.toPx()),
                    top + 14.dp.toPx(),
                    paint,
                )
            }
            if (selectedStop in viewStart..viewEnd) {
                drawText(
                    "B ${if (selectedStopHigh) "RISE" else "FALL"}  " +
                        formatTraceTime(ticksToNanoseconds(selectedStop)),
                    (xFor(selectedStop) + 5.dp.toPx()).coerceAtMost(right - 110.dp.toPx()),
                    size.height / 2f + 18.dp.toPx(),
                    paint,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDigitalChannel(
    events: List<WaveformEvent>,
    channel: Int,
    viewStart: Long,
    viewEnd: Long,
    left: Float,
    width: Float,
    bandTop: Float,
    bandBottom: Float,
    color: Color,
) {
    val relevant = events.filter { it.channel == channel }
    var high = relevant.lastOrNull { it.offsetTicks < viewStart }?.high ?: false
    val highY = bandTop + (bandBottom - bandTop) * 0.28f
    val lowY = bandTop + (bandBottom - bandTop) * 0.72f
    fun y() = if (high) highY else lowY
    fun x(tick: Long) = left +
        ((tick - viewStart).toDouble() / (viewEnd - viewStart).coerceAtLeast(1L) * width).toFloat()

    val path = Path().apply { moveTo(left, y()) }
    relevant.filter { it.offsetTicks in viewStart..viewEnd }.forEach { event ->
        val eventX = x(event.offsetTicks)
        path.lineTo(eventX, y())
        high = event.high
        path.lineTo(eventX, y())
    }
    path.lineTo(left + width, y())
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
}

private fun automaticOffset(absoluteTicks: Long, baseTicks: Long): Long =
    (absoluteTicks - baseTicks) and 0xFFFFFFFFL

private fun edgeHighAt(
    events: List<WaveformEvent>,
    channel: Int,
    tick: Long,
): Boolean? = events.firstOrNull {
    it.channel == channel && it.offsetTicks == tick
}?.high

private fun nearestEdge(
    events: List<WaveformEvent>,
    channel: Int,
    tick: Long,
    high: Boolean,
): Long? =
    events.asSequence().filter { it.channel == channel && it.high == high }
        .minByOrNull { abs(it.offsetTicks - tick) }?.offsetTicks

private fun previousEdge(
    events: List<WaveformEvent>,
    channel: Int,
    tick: Long,
    high: Boolean,
): Long? =
    events.asSequence().filter {
        it.channel == channel && it.high == high && it.offsetTicks < tick
    }
        .maxOfOrNull { it.offsetTicks }

private fun nextEdge(
    events: List<WaveformEvent>,
    channel: Int,
    tick: Long,
    high: Boolean,
): Long? =
    events.asSequence().filter {
        it.channel == channel && it.high == high && it.offsetTicks > tick
    }
        .minOfOrNull { it.offsetTicks }

private fun formatSignedTime(ns: Long): String =
    (if (ns >= 0) "+" else "-") + formatTraceTime(abs(ns))

private fun formatTraceTime(ns: Long): String {
    val sign = if (ns < 0) "-" else ""
    val magnitude = abs(ns)
    return sign + when {
        magnitude < 1_000L -> "$magnitude ns"
        magnitude < 1_000_000L -> "%.3f µs".format(magnitude / 1_000.0)
        magnitude < 1_000_000_000L -> "%.3f ms".format(magnitude / 1_000_000.0)
        else -> "%.3f s".format(magnitude / 1_000_000_000.0)
    }
}
