package com.chrono.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Builds export files and hands them to the system share sheet: a CSV of all
 * results (spreadsheet-friendly) plus the raw calibration history JSONL.
 */
object Exporter {

    fun export(context: Context, results: List<TestResult>, simulated: Boolean = false) {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tag = if (simulated) "SIM_" else ""

        val csv = File(dir, "chrono_results_$tag$stamp.csv")
        csv.writeText(buildString {
            appendLine(
                "simulated,label,shot_type,disruptor_type_model,target,standoff_value,standoff_unit," +
                    "disruptor_loading,projectile_type,pass_fail,special_notes,source,date_iso," +
                    "mcu_serial,hardware_revision,firmware_version,battery_mv,boot_id,reset_cause," +
                    "result_flags,port_flags,raw_start_ticks,raw_stop_ticks,format_version,crc_valid," +
                    "timing_fault,split_time,split_ns,split_ms,distance_m,measurement_error_m," +
                    "measurement_error_unit,velocity_mps,velocity_fps"
            )
            for (r in results) {
                val date = r.epochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
                appendLine(
                    simulated.toString() + "," +
                        esc(r.label) + "," + esc(r.shotType.ifBlank { "Standard" }) + "," +
                        esc(r.tool) + "," + esc(r.target) + "," +
                        (r.targetDistValue?.toString() ?: "") + "," + r.targetDistUnit + "," +
                        esc(r.disruptorLoading) + "," + esc(r.projectileType.ifBlank { "Water" }) + "," +
                        esc(r.passFail) + "," + esc(r.specialNotes.ifBlank { r.outcome }) + "," +
                        (if (r.isManual) "manual" else "device") + "," +
                        date + "," + esc(r.deviceSerial) + "," + r.hardwareRevision + "," +
                        esc(r.firmwareVersion) + "," + (r.batteryMv ?: "") + "," + r.bootId + "," +
                        r.resetCause + "," + r.resultFlags + "," + r.portFlags + "," +
                        r.rawStartTicks + "," + r.rawStopTicks + "," + r.formatVersion + "," +
                        r.crcValid + "," + esc(r.timingFaultText().orEmpty()) + "," +
                        esc(r.splitTimeText()) + "," + r.signedSplitNs + "," +
                        String.format(Locale.US, "%.6f", r.splitMillis) + "," +
                        String.format(Locale.US, "%.5f", r.distanceM) + "," +
                        String.format(Locale.US, "%.5f", r.measurementErrorM) + "," +
                        r.measurementErrorUnit + "," +
                        String.format(Locale.US, "%.3f", r.metersPerSecond) + "," +
                        String.format(Locale.US, "%.2f", r.feetPerSecond)
                )
            }
        })

        val uris = arrayListOf(uriFor(context, csv))
        val cal = File(context.filesDir, if (simulated) "cal_history_sim.jsonl" else "cal_history.jsonl")
        if (cal.exists()) {
            val calCopy = File(dir, "chrono_cal_$tag$stamp.jsonl")
            cal.copyTo(calCopy, overwrite = true)
            uris.add(uriFor(context, calCopy))
        }
        val health = File(
            context.filesDir,
            if (simulated) "health_history_sim.jsonl" else "health_history.jsonl"
        )
        if (health.exists()) {
            val healthCopy = File(dir, "chrono_health_$tag$stamp.jsonl")
            health.copyTo(healthCopy, overwrite = true)
            uris.add(uriFor(context, healthCopy))
        }

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export Chrono Logger data"))
    }

    private fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}
