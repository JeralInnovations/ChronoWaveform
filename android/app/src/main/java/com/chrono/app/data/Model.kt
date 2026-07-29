package com.chrono.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One recorded split. Stored locally in results.json. */
data class TestResult(
    val uid: String,
    val deviceResultId: Int,
    /** Split between the two gates, in nanoseconds (62.5 ns hardware resolution). */
    val splitNs: Long,
    val distanceM: Double,
    var label: String,
    /** null = device had no synced clock at the time of the shot */
    var epochMillis: Long?,
    var tool: String = "",
    var shotType: String = "Standard",
    var disruptorLoading: String = "",
    var projectileType: String = "Water",
    /** distance to target kept as value+unit so it can be converted later */
    var targetDistValue: Double? = null,
    var targetDistUnit: String = "in",
    var target: String = "",
    var passFail: String = "",
    var specialNotes: String = "",
    /** legacy outcome notes; new logs use specialNotes */
    var outcome: String = "",
    /** set on manual entries (deviceResultId < 0) that were typed in, not measured */
    var manualVelocityMps: Double? = null,
    /** MCU identity captured from the hardware info characteristic for traceability. */
    var deviceSerial: String = "",
    var resultFlags: Int = 0,
    var rawStartTicks: Long = 0,
    var rawStopTicks: Long = 0,
    var batteryMv: Int? = null,
    var portFlags: Int = 0,
    var bootId: Long = 0,
    var resetCause: Long = 0,
    var hardwareRevision: Int = 0,
    var firmwareVersion: String = "",
    var formatVersion: Int = 1,
    var crcValid: Boolean = true,
    var traceFormatVersion: Int = 0,
    var traceBaseTicks: Long = 0,
    var traceFlags: Int = 0,
    var traceData: String = "",
    var reviewedSplitNs: Long? = null,
    var reviewedStartOffsetTicks: Long? = null,
    var reviewedStopOffsetTicks: Long? = null,
    var reviewedAtMillis: Long? = null,
    /** folder id under ChronoData holding this shot's log and photos */
    var shotFolder: String = "",
    /** user-chosen cover image URI for this shot ("" = use the first photo) */
    var thumbnailUri: String = "",
) {
    val isManual: Boolean get() = deviceResultId < 0
    val effectiveSplitNs: Long get() = reviewedSplitNs ?: splitNs
    val isWaveformReviewed: Boolean get() = reviewedSplitNs != null
    val hasWaveform: Boolean get() = traceFormatVersion > 0
    val splitSeconds: Double get() = effectiveSplitNs / 1_000_000_000.0
    val splitMillis: Double get() = effectiveSplitNs / 1_000_000.0
    val metersPerSecond: Double
        get() = manualVelocityMps
            ?: if (effectiveSplitNs != 0L && distanceM > 0) distanceM / splitSeconds else 0.0
    val feetPerSecond: Double get() = metersPerSecond * 3.28084

    fun timingFaultText(): String? = when {
        resultFlags and 0x80 != 0 -> "High-frequency clock failed to stabilize"
        resultFlags and 0x04 != 0 -> "STOP triggered before START"
        resultFlags and 0x08 != 0 -> "STOP did not trigger before timeout"
        resultFlags and 0x10 != 0 -> "Split time below allowed range"
        resultFlags and 0x20 != 0 -> "Split time above allowed range"
        !crcValid -> "Result packet CRC failed"
        else -> null
    }

    fun splitTimeText(): String {
        val value = effectiveSplitNs
        if (value == 0L) return "not recorded"
        val magnitude = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            magnitude < 1_000L -> "$sign${magnitude}ns"
            magnitude < 1_000_000L -> sign + formatWholeish(magnitude / 1_000.0, "us")
            magnitude < 1_000_000_000L -> sign + formatWholeish(magnitude / 1_000_000.0, "ms")
            else -> sign + formatWholeish(magnitude / 1_000_000_000.0, "s")
        }
    }

    fun waveformEvents(): List<WaveformEvent> = WaveformCodec.decode(traceData)

    fun targetDistanceText(): String? =
        targetDistValue?.let {
            val v = if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            "$v $targetDistUnit"
        }

    fun formattedDate(): String? {
        val ms = epochMillis ?: return null
        return DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))
    }
}

private fun formatWholeish(value: Double, unit: String): String {
    val text = when {
        value >= 100 -> String.format(Locale.US, "%.0f", value)
        value >= 10 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }.let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }
    return "$text$unit"
}

/** Units offered for "distance to target" — value + unit are stored separately. */
val TARGET_DIST_UNITS = listOf("in", "ft", "yd", "m", "cm")

/** Parse the old free-text "targetDistance" field, e.g. "25 yd". */
internal fun legacyDistValue(s: String): Double? =
    Regex("^\\s*([0-9]+\\.?[0-9]*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()

internal fun legacyDistUnit(s: String): String {
    val unit = Regex("([a-zA-Z]+)\\s*$").find(s)?.groupValues?.get(1)?.lowercase() ?: ""
    return if (unit in TARGET_DIST_UNITS) unit else "in"
}

enum class DistanceUnit(val label: String, val toMeters: Double) {
    INCHES("in", 0.0254),
    MILLIMETERS("mm", 0.001),
    CENTIMETERS("cm", 0.01),
    FEET("ft", 0.3048),
}

/** Dead-simple JSON file persistence — no database needed for a results log. */
class ResultStore(context: Context, simulation: Boolean = false) {
    // Simulated runs persist to their own file so demo data never mixes with
    // real results.
    private val file = File(context.filesDir, if (simulation) "results_sim.json" else "results.json")

    fun load(): List<TestResult> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val legacyOutcome = o.optString("outcome", "")
                TestResult(
                    uid = o.getString("uid"),
                    deviceResultId = o.getInt("deviceResultId"),
                    splitNs = o.getLong("splitNs"),
                    distanceM = o.getDouble("distanceM"),
                    label = o.optString("label", ""),
                    epochMillis = o.optLong("epochMillis", -1L).takeIf { it > 0 },
                    tool = o.optString("tool", ""),
                    shotType = o.optString("shotType", "Standard").ifBlank { "Standard" },
                    disruptorLoading = o.optString("disruptorLoading", ""),
                    projectileType = o.optString("projectileType", "Water").ifBlank { "Water" },
                    targetDistValue = o.optDouble("targetDistValue").takeIf { !it.isNaN() }
                        ?: legacyDistValue(o.optString("targetDistance", "")),
                    targetDistUnit = o.optString("targetDistUnit").ifBlank {
                        legacyDistUnit(o.optString("targetDistance", ""))
                    },
                    target = o.optString("target", ""),
                    passFail = o.optString("passFail", ""),
                    specialNotes = o.optString("specialNotes", legacyOutcome),
                    outcome = legacyOutcome,
                    manualVelocityMps = o.optDouble("manualVelocityMps").takeIf { !it.isNaN() },
                    deviceSerial = o.optString("deviceSerial", ""),
                    resultFlags = o.optInt("resultFlags", 0),
                    rawStartTicks = o.optLong("rawStartTicks", 0),
                    rawStopTicks = o.optLong("rawStopTicks", 0),
                    batteryMv = o.optInt("batteryMv", -1).takeIf { it >= 0 },
                    portFlags = o.optInt("portFlags", 0),
                    bootId = o.optLong("bootId", 0),
                    resetCause = o.optLong("resetCause", 0),
                    hardwareRevision = o.optInt("hardwareRevision", 0),
                    firmwareVersion = o.optString("firmwareVersion", ""),
                    formatVersion = o.optInt("formatVersion", 1),
                    crcValid = o.optBoolean("crcValid", true),
                    traceFormatVersion = o.optInt("traceFormatVersion", 0),
                    traceBaseTicks = o.optLong("traceBaseTicks", 0),
                    traceFlags = o.optInt("traceFlags", 0),
                    traceData = o.optString("traceData", ""),
                    reviewedSplitNs = o.optLong("reviewedSplitNs", Long.MIN_VALUE)
                        .takeUnless { it == Long.MIN_VALUE },
                    reviewedStartOffsetTicks =
                        o.optLong("reviewedStartOffsetTicks", Long.MIN_VALUE)
                            .takeUnless { it == Long.MIN_VALUE },
                    reviewedStopOffsetTicks =
                        o.optLong("reviewedStopOffsetTicks", Long.MIN_VALUE)
                            .takeUnless { it == Long.MIN_VALUE },
                    reviewedAtMillis = o.optLong("reviewedAtMillis", -1L)
                        .takeIf { it > 0 },
                    shotFolder = o.optString("shotFolder", ""),
                    thumbnailUri = o.optString("thumbnailUri", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(results: List<TestResult>) {
        val arr = JSONArray()
        for (r in results) {
            arr.put(
                JSONObject()
                    .put("uid", r.uid)
                    .put("deviceResultId", r.deviceResultId)
                    .put("splitNs", r.splitNs)
                    .put("distanceM", r.distanceM)
                    .put("label", r.label)
                    .put("epochMillis", r.epochMillis ?: -1L)
                    .put("tool", r.tool)
                    .put("shotType", r.shotType.ifBlank { "Standard" })
                    .put("disruptorLoading", r.disruptorLoading)
                    .put("projectileType", r.projectileType.ifBlank { "Water" })
                    .put("targetDistUnit", r.targetDistUnit)
                    .put("target", r.target)
                    .put("passFail", r.passFail)
                    .put("specialNotes", r.specialNotes)
                    .put("outcome", r.specialNotes.ifBlank { r.outcome })
                    .put("deviceSerial", r.deviceSerial)
                    .put("resultFlags", r.resultFlags)
                    .put("rawStartTicks", r.rawStartTicks)
                    .put("rawStopTicks", r.rawStopTicks)
                    .put("portFlags", r.portFlags)
                    .put("bootId", r.bootId)
                    .put("resetCause", r.resetCause)
                    .put("hardwareRevision", r.hardwareRevision)
                    .put("firmwareVersion", r.firmwareVersion)
                    .put("formatVersion", r.formatVersion)
                    .put("crcValid", r.crcValid)
                    .put("traceFormatVersion", r.traceFormatVersion)
                    .put("traceBaseTicks", r.traceBaseTicks)
                    .put("traceFlags", r.traceFlags)
                    .put("traceData", r.traceData)
                    .put("shotFolder", r.shotFolder)
                    .put("thumbnailUri", r.thumbnailUri)
                    .apply {
                        r.targetDistValue?.let { put("targetDistValue", it) }
                        r.manualVelocityMps?.let { put("manualVelocityMps", it) }
                        r.batteryMv?.let { put("batteryMv", it) }
                        r.reviewedSplitNs?.let { put("reviewedSplitNs", it) }
                        r.reviewedStartOffsetTicks?.let { put("reviewedStartOffsetTicks", it) }
                        r.reviewedStopOffsetTicks?.let { put("reviewedStopOffsetTicks", it) }
                        r.reviewedAtMillis?.let { put("reviewedAtMillis", it) }
                    }
            )
        }
        file.writeText(arr.toString())
    }
}
