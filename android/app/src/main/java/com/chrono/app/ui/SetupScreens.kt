@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chrono.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.Proto
import com.chrono.app.data.DistanceUnit
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.Teal
import com.chrono.app.ui.theme.TextDim

@Composable
private fun SetupFieldLabel(label: String, help: String) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.size(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .border(1.dp, Teal, RoundedCornerShape(9.dp))
                .clickable { open = true },
        ) {
            Text("i", color = Teal, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { Text(help, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}

/**
 * Shared "plug in and test a sensor" pane — used by the setup wizard and
 * by the retest dialog on the dashboard.
 */
@Composable
fun VerifyPane(
    sensor: Int,
    deviceState: Int,
    modifier: Modifier = Modifier,
    verifiedOverride: Boolean = false,
) {
    val listening = deviceState == if (sensor == 1) Proto.ST_VERIFY1 else Proto.ST_VERIFY2
    val verified = verifiedOverride ||
        deviceState == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
    val role = if (sensor == 1) "START" else "STOP"

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "SENSOR $sensor — $role",
            style = MaterialTheme.typography.labelLarge,
            color = Amber,
        )
        Spacer(Modifier.height(20.dp))

        TerminalGraphic(pulsing = listening, verified = verified)
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Text("+", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium)
            Text("–", color = TextDim, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(28.dp))

        if (verified) {
            Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Sensor $sensor detected!",
                style = MaterialTheme.typography.headlineMedium,
                color = Good,
            )
        } else {
            Text(
                "Insert the $role sensor leads into port $sensor.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Tap the sensor once.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Amber,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(10.dp))
                Text("Listening on input $sensor…", color = TextDim)
            }
        }
    }
}

/** Step 1 of the wizard: measure both ports with nothing plugged in. */
@Composable
fun BaselineScreen(vm: ChronoViewModel, connState: ConnState) {
    val b1 = vm.calData["b1"]
    val b2 = vm.calData["b2"]
    val baselineDone = b1 != null && b2 != null
    val baselineWarning = baselineDone && (b1?.isUsable != true || b2?.isUsable != true)
    val highBaselinePorts = listOfNotNull(
        b1?.takeIf { vm.baselineTooHigh(it) }?.let { "Port 1: ${vm.baselineSignatureText(it)}" },
        b2?.takeIf { vm.baselineTooHigh(it) }?.let { "Port 2: ${vm.baselineSignatureText(it)}" },
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  1/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(24.dp))

        if (connState == ConnState.RECONNECTING) {
            ReconnectingBanner()
            Spacer(Modifier.height(16.dp))
        }

        Text("Port baseline", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Leave both ports empty. This sets the zero point for sensor checks.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (vm.calRunning) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("Measuring ports…", color = TextDim)
        } else {
            Button(onClick = { vm.startBaselineCal() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (b1 == null && b2 == null) "Run baseline" else "Run baseline again")
            }
        }
        Spacer(Modifier.height(20.dp))

        CalRow("Port 1", vm.calData["b1"], vm::rcDelayText)
        Spacer(Modifier.height(8.dp))
        CalRow("Port 2", vm.calData["b2"], vm::rcDelayText)
        if (highBaselinePorts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Warning: empty-port RC delay appears too high to be the port alone. " +
                    "Limit is 3 us or above; measured ${highBaselinePorts.joinToString(", ")}. " +
                    "Remove anything connected and run baseline again.",
                color = Bad,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (baselineWarning) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Baseline failed. Tap tests can continue; RC checks will be limited.",
                color = Amber,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { vm.cancelCalibrationToDashboard() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Cancel") }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.continueToSensor1() },
            enabled = baselineDone && !vm.calRunning,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Continue to sensor 1") }
    }
}

@Composable
private fun CalRow(label: String, entry: com.chrono.app.CalEntry?, rcDelayText: (Long) -> String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        val text = when {
            entry == null -> "-"
            !entry.isUsable && entry.status == 2 -> "No usable samples - n=${entry.samples}"
            !entry.isUsable -> "Incomplete - n=${entry.samples}"
            else -> "%s  -  sigma %d ns".format(
                rcDelayText(entry.medianNs),
                entry.stddevNs,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry == null || entry.status == 0) TextDim else Amber,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SensorSetupScreen(
    vm: ChronoViewModel,
    sensor: Int,
    deviceState: Int,
    connState: ConnState,
    onContinue: () -> Unit,
) {
    // Three explicit, user-driven steps:
    //   attach   — plug the sensor in (nothing armed)
    //   measure  — RC signature check vs the bare-port baseline
    //   tap      — impact/voltage-rise test (a separate check)
    var step by remember(sensor) { mutableStateOf("attach") }
    var wasVerified by remember(sensor) { mutableStateOf(false) }
    if (deviceState == (if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK)) {
        wasVerified = true
    }

    val loadNs = vm.channelLoadNs(sensor)
    val baselineEntry = vm.calData["b$sensor"]
    val loadEntry = vm.calData["l$sensor"]
    val role = if (sensor == 1) "START" else "STOP"
    val scrollState = rememberScrollState()

    // Successful detection adds status content above the action area. Keep the
    // newly enabled Continue button visible instead of requiring a manual swipe.
    LaunchedEffect(wasVerified, scrollState.maxValue) {
        if (wasVerified) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  ${sensor + 1}/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(8.dp))
        Text(
            "SENSOR $sensor — $role",
            style = MaterialTheme.typography.labelLarge,
            color = Amber,
        )
        Spacer(Modifier.height(16.dp))

        if (connState == ConnState.RECONNECTING) {
            ReconnectingBanner()
            Spacer(Modifier.height(16.dp))
        }

        when (step) {
            "attach" -> {
                TerminalGraphic(pulsing = false, verified = false)
                Spacer(Modifier.height(24.dp))
                Text(
                    "Install the $role sensor in port $sensor. Nothing is armed.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { vm.startLoadedCal(sensor); step = "measure" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Text(
                        "Sensor plugged in - check RC signature",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.cancelCalibrationToDashboard() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) { Text("Cancel") }
            }

            "measure" -> {
                Text(
                    "RC signature check",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sensor and cable should add RC delay over baseline.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                if (vm.calRunning) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Checking RC signature...", color = TextDim)
                } else when {
                    loadEntry == null -> Text(
                        "No reading yet. Re-measure or run the tap test.",
                        color = Amber, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    loadEntry.isUsable != true -> Text(
                        "No usable RC samples. Check leads or run the tap test.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    baselineEntry?.isUsable != true -> Text(
                        "Baseline is unavailable. Use the tap test to verify the sensor.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    loadNs != null && loadNs > 250 -> {
                        Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Sensor detected: RC delay +%s over baseline.".format(vm.rcDelayText(loadNs)),
                            color = Good,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> Text(
                        "No RC signature increase. Check clips or run the tap test.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { vm.cancelCalibrationToDashboard() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) { Text("Cancel") }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.startLoadedCal(sensor) },
                        enabled = !vm.calRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("Re-measure") }
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = { vm.beginTapTest(sensor); step = "tap" },
                        enabled = !vm.calRunning && loadEntry != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Tap test") }
                }
            }

            else -> {
                Text(
                    "Tap test — impact detection",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap once to confirm impact detection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                VerifyPane(sensor = sensor, deviceState = deviceState, verifiedOverride = wasVerified)
                if (vm.isSimulation && !wasVerified) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.simulateTap() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Simulate sensor tap") }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { vm.cancelCalibrationToDashboard() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) { Text("Cancel") }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onContinue,
                    enabled = wasVerified,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text(if (sensor == 1) "Continue to sensor 2" else "Continue") }
            }
        }
    }
}

@Composable
fun DistanceScreen(vm: ChronoViewModel) {
    var text by remember {
        mutableStateOf(if (vm.distanceValue > 0) trimZeros(vm.distanceInUnit()) else "")
    }
    var measurementErrorText by remember {
        mutableStateOf(trimZeros(vm.measurementErrorValue))
    }
    var unit by remember { mutableStateOf(vm.distanceUnit) }
    var errorUnit by remember { mutableStateOf(vm.measurementErrorUnit) }
    var menuOpen by remember { mutableStateOf(false) }
    var errorMenuOpen by remember { mutableStateOf(false) }
    val value = text.replace(',', '.').toDoubleOrNull()
    val measurementError = measurementErrorText.replace(',', '.').toDoubleOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  4/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(48.dp))
        Text("SENSOR SPACING", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Measure START sensor to STOP sensor.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { SetupFieldLabel("DISTANCE", "Exact START-to-STOP sensor spacing used for velocity and GAE calculations.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.size(12.dp))
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                TextField(
                    value = unit.label,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor().size(width = 96.dp, height = 56.dp),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DistanceUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(u.label) },
                            onClick = {
                                val oldUnit = unit
                                value?.let { text = trimZeros(it * oldUnit.toMeters / u.toMeters) }
                                unit = u
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = measurementErrorText,
                onValueChange = { measurementErrorText = it },
                modifier = Modifier.weight(1f),
                label = {
                    SetupFieldLabel(
                        "MEASUREMENT ERROR",
                        "Your estimated +/- error when measuring the START-to-STOP spacing. " +
                            "For example, enter 0.25 and select in for a spacing measured " +
                            "to +/- 0.25 in. This is combined with timing uncertainty in " +
                            "the Guaranteed Accuracy Envelope (GAE).",
                    )
                },
                prefix = { Text("+/-") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.size(12.dp))
            ExposedDropdownMenuBox(
                expanded = errorMenuOpen,
                onExpandedChange = { errorMenuOpen = it },
            ) {
                TextField(
                    value = errorUnit.label,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor().size(width = 96.dp, height = 56.dp),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = errorMenuOpen)
                    },
                )
                ExposedDropdownMenu(
                    expanded = errorMenuOpen,
                    onDismissRequest = { errorMenuOpen = false },
                ) {
                    DistanceUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(u.label) },
                            onClick = {
                                val oldUnit = errorUnit
                                measurementError?.let {
                                    measurementErrorText =
                                        trimZeros(it * oldUnit.toMeters / u.toMeters)
                                }
                                errorUnit = u
                                errorMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Spacing sets velocity directly. Measurement Error is included in every GAE result.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextDim,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                vm.saveDistance(value ?: 0.0, unit, measurementError ?: 0.0, errorUnit)
            },
            enabled = value != null && value > 0 && measurementError != null &&
                measurementError >= 0 &&
                measurementError * errorUnit.toMeters < value * unit.toMeters,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Save distance") }
    }
}

fun trimZeros(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
