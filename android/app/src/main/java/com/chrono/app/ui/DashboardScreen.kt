package com.chrono.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.DeviceStatus
import com.chrono.app.ble.HealthStatus
import com.chrono.app.ble.Proto
import com.chrono.app.ble.SimFault
import com.chrono.app.data.Exporter
import com.chrono.app.data.TARGET_DIST_UNITS
import com.chrono.app.data.TestResult
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.PanelHigh
import com.chrono.app.ui.theme.Teal
import com.chrono.app.ui.theme.TextDim
import kotlin.math.roundToInt
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReconnectingBanner() {
    val t = rememberInfiniteTransition(label = "recon")
    val a by t.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "reconAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bad.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BluetoothDisabled, null,
            tint = Bad, modifier = Modifier.size(18.dp).alpha(a),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "Bluetooth link lost. Reconnecting automatically when the logger is back in range.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bad,
        )
    }
}

@Composable
fun DashboardScreen(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    val state = deviceStatus?.state ?: -1
    val armed = state == Proto.ST_ARMED
    val running = state == Proto.ST_RUNNING
    val health by vm.ble.health.collectAsState()
    var editing by remember { mutableStateOf<TestResult?>(null) }
    var waveformReviewUid by remember { mutableStateOf<String?>(null) }
    var manualEntry by remember { mutableStateOf(false) }
    // (photo uri, owning result uid) so the viewer can offer "set as cover"
    var fullscreenPhoto by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    var promptPhotoPreview by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedPromptPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedSetupPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var dismissedLowBatteryMv by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val photoRevision = vm.photoRevision
    val batteryMv = deviceStatus?.batteryMv
    val showLowBattery = connState == ConnState.CONNECTED &&
        deviceStatus?.lowBattery == true &&
        batteryMv != dismissedLowBatteryMv

    // System camera writing straight into the shot folder: no CAMERA permission.
    var pendingPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> pendingPhoto?.let { vm.photoSaved(ok, it) }; pendingPhoto = null }
    val pickPromptPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> vm.importPromptPhotos(uris) }

    // Manual-logging mode: no device, so hide everything device-specific.
    val offline = connState == ConnState.DISCONNECTED
    val setupPhotos = remember(photoRevision, vm.pendingLabel, vm.isSimulation) { vm.setupPhotos() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar(vm, connState, deviceStatus) }

        if (connState == ConnState.RECONNECTING) {
            item { ReconnectingBanner() }
        }

        if (!offline) {
            item {
                OutlinedButton(
                    onClick = { vm.redoSetup() },
                    enabled = connState == ConnState.CONNECTED && !armed && !running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set up new test") }
            }
            if (vm.setupPhotosNeeded && vm.canAddSetupPhotos &&
                connState == ConnState.CONNECTED && !armed && !running
            ) {
                item {
                    Button(
                        onClick = { vm.requestSetupPhotos() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE00000),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Add setup photos")
                    }
                }
            }
        }

        if (vm.isSimulation) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Teal.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Simulation mode. No hardware connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Teal,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.simulateSignalLoss() }) {
                        Text(
                            if (connState == ConnState.RECONNECTING) "Restore signal"
                            else "Drop signal",
                            color = Teal,
                        )
                    }
                }
            }
            item { SimulationFaultControl(vm) }
        }

        if (offline) {
            item {
                Text(
                    "Manual logging. Entries, photos, " +
                        "and exports work; measuring needs the device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
            }
        } else {
            item { RigCard(vm, enabled = connState == ConnState.CONNECTED && !armed && !running) }

            item {
                ChannelsCard(
                    vm,
                    enabled = connState == ConnState.CONNECTED && !armed && !running && !vm.calRunning,
                )
            }

            item {
                PortHealthCard(
                    health = health,
                    checking = state == Proto.ST_CHECKING,
                    enabled = connState == ConnState.CONNECTED && !armed && !running,
                    onCheck = { vm.checkPorts() },
                    onIdentify = { vm.identifyLogger() },
                    onOverride = { vm.armWithOverride() },
                )
            }

            item { NextTestCard(vm, armed = armed || running) }

            item {
                ArmButton(
                    armed = armed,
                    running = running,
                    connected = (connState == ConnState.CONNECTED || connState == ConnState.RECONNECTING) &&
                        state != Proto.ST_CHECKING,
                    sensorsReady = vm.sensor1Ready && vm.sensor2Ready &&
                        deviceStatus?.batteryLocked != true,
                    batteryLocked = deviceStatus?.batteryLocked == true,
                    onArm = { vm.arm() },
                    onDisarm = { vm.disarm() },
                )
            }

            if (vm.canAddSetupPhotos && setupPhotos.isNotEmpty()) {
                item {
                    SetupPhotoStrip(
                        photos = setupPhotos,
                        selected = selectedSetupPhoto,
                        onOpen = { promptPhotoPreview = it },
                        onSelect = { selectedSetupPhoto = if (selectedSetupPhoto == it) null else it },
                        onDelete = {
                            selectedSetupPhoto?.let { vm.deletePromptPhoto(it) }
                            selectedSetupPhoto = null
                        },
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { manualEntry = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.EditNote, null, tint = TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Log manual entry", color = TextDim)
            }
        }

        if (vm.results.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
                ) {
                    Text(
                        "RESULTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.openDataFolder() }) {
                        Icon(
                            Icons.Filled.FolderOpen, null,
                            tint = TextDim, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Files", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { Exporter.export(context, vm.results.toList(), vm.isSimulation) }) {
                        Icon(
                            Icons.Filled.Share, null,
                            tint = TextDim, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Export", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.results, key = { it.uid }) { r ->
                ResultCard(
                    r,
                    latest = r == vm.results.firstOrNull(),
                    accuracyEnvelopePercent = vm.accuracyEnvelopePercentFor(r),
                    coverFor = { vm.photosFor(r) },
                    onEdit = { editing = r },
                    onOpenPhoto = { fullscreenPhoto = it to r.uid },
                    onReviewWaveform = { waveformReviewUid = r.uid },
                )
            }
        }
    }

    // Sensor-attach flow: fit wire -> RC signature check -> tap test.
    // The input is deliberately NOT armed until the tap step, so movement
    // during placement can't trigger anything.
    vm.retestSensor?.let { sensor ->
        var step by remember(sensor) { mutableStateOf("attach") }
        val verified = state == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
        AlertDialog(
            onDismissRequest = { vm.cancelCalibrationToDashboard() },
            title = { Text("Sensor $sensor — ${if (sensor == 1) "start" else "stop"}") },
            text = {
                Column {
                    when (step) {
                        "attach" -> Text(
                        "Install the sensor in port $sensor. Nothing is armed.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        "measure" -> {
                            if (vm.calRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = Amber,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("Checking RC signature...", color = TextDim)
                                }
                            } else {
                                val load = vm.channelLoadNs(sensor)
                                when {
                                    load == null -> Text(
                                        "Couldn't measure. Check the connection to the " +
                                            "device and try again.",
                                        color = Amber,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    load > 250 -> Text(
                                        "Sensor detected: RC delay +%s over baseline."
                                            .format(vm.rcDelayText(load)),
                                        color = Good,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    else -> Text(
                                        "No RC signature increase. Check clips and re-measure.",
                                        color = Amber,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        else -> {
                            VerifyPane(
                                sensor = sensor,
                                deviceState = state,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (vm.isSimulation && !verified) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { vm.simulateTap() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Simulate sensor tap") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (step) {
                    "attach" -> TextButton(onClick = {
                        vm.startLoadedCal(sensor)
                        step = "measure"
                    }) { Text("Sensor attached") }
                    "measure" -> Row {
                        TextButton(
                            onClick = { vm.startLoadedCal(sensor) },
                            enabled = !vm.calRunning,
                        ) { Text("Re-measure") }
                        TextButton(
                            onClick = {
                                vm.beginTapTest(sensor)
                                step = "tap"
                            },
                            enabled = !vm.calRunning,
                        ) { Text("Continue") }
                    }
                    else -> TextButton(
                        onClick = { vm.finishRetest(true) },
                        enabled = verified,
                    ) { Text("Done") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelCalibrationToDashboard() }) { Text("Cancel") }
            },
        )
    }

    // Shots received (e.g. after walking back into range): review, then photos.
    if (vm.newShots.isNotEmpty() && vm.retestSensor == null) {
        val waveformShot = vm.newShots.firstOrNull { it.hasWaveform }
        val waitingForWaveform = vm.newShots.any {
            it.firmwareVersion.startsWith("3.") && !it.hasWaveform
        }
        AlertDialog(
            onDismissRequest = { vm.dismissShotReview() },
            title = {
                Text(if (vm.newShots.size == 1) "Shot recorded" else "${vm.newShots.size} shots received")
            },
            text = {
                Column {
                    for (s in vm.newShots) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.1f".format(s.feetPerSecond),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 26.sp,
                                color = Amber,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("ft/s", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(12.dp))
                            Text(
                                s.splitTimeText(),
                                color = TextDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (s.label.isNotBlank()) {
                            Text(s.label, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                if (waveformShot != null) {
                    TextButton(onClick = { waveformReviewUid = waveformShot.uid }) {
                        Text("Review waveform")
                    }
                } else {
                    TextButton(
                        onClick = { vm.dismissShotReview() },
                        enabled = !waitingForWaveform,
                    ) { Text(if (waitingForWaveform) "Receiving waveform…" else "Continue") }
                }
            },
            dismissButton = {
                if (waveformShot != null || waitingForWaveform) {
                    TextButton(onClick = { vm.dismissShotReview() }) {
                        Text(if (waitingForWaveform) "Continue without waveform" else "Use automatic")
                    }
                }
            },
        )
    }

    vm.resultPrompt?.let { r ->
        EditResultDialog(
            result = r,
            title = "Log result",
            dismissText = "Close",
            showDelete = false,
            showRecordedValues = true,
            onDismiss = { vm.finishResultPrompt() },
            onSave = { label, shotType, tool, target, tdVal, tdUnit, loading, projectile, passFail, notes, epochMillis ->
                vm.updateResult(
                    r.uid, label, shotType, tool, loading, projectile, target, tdVal, tdUnit,
                    passFail, notes, epochMillis,
                )
                vm.finishResultPrompt()
            },
            onAttachPhotos = { uris -> vm.attachPhotosToResult(r.uid, uris) },
            photosFor = { vm.photosFor(r) },
            onOpenPhoto = { fullscreenPhoto = it to r.uid },
            onDeletePhoto = { uri -> vm.deleteResultPhoto(r.uid, uri) },
            onDelete = {},
        )
    }

    editing?.let { r ->
        EditResultDialog(
            result = r,
            onDismiss = { editing = null },
            onSave = { label, shotType, tool, target, tdVal, tdUnit, loading, projectile, passFail, notes, epochMillis ->
                vm.updateResult(
                    r.uid, label, shotType, tool, loading, projectile, target, tdVal, tdUnit,
                    passFail, notes, epochMillis,
                )
                editing = null
            },
            onAttachPhotos = { uris -> vm.attachPhotosToResult(r.uid, uris) },
            photosFor = { vm.photosFor(r) },
            onOpenPhoto = { fullscreenPhoto = it to r.uid },
            onDeletePhoto = { uri -> vm.deleteResultPhoto(r.uid, uri) },
            onDelete = {
                vm.deleteResult(r.uid)
                editing = null
            },
        )
    }

    if (vm.showFullLog) {
        FullLogDialog(
            vm = vm,
            onExit = { vm.hideFullLog() },
            onEdit = {
                vm.hideFullLog()
                editing = it
            },
            onOpenPhoto = { uri, uid -> fullscreenPhoto = uri to uid },
        )
    }

    fullscreenPhoto?.let { (uri, uid) ->
        Dialog(
            onDismissRequest = { fullscreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { fullscreenPhoto = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OutlinedButton(onClick = {
                        vm.setResultThumbnail(uid, uri.toString())
                        fullscreenPhoto = null
                    }) { Text("Set as shot thumbnail") }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap image to close",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }

    waveformReviewUid?.let { uid ->
        vm.results.firstOrNull { it.uid == uid }?.let { result ->
            WaveformReviewDialog(
                result = result,
                onDismiss = { waveformReviewUid = null },
                onApply = { start, stop ->
                    vm.applyReviewedTiming(uid, start, stop)
                    waveformReviewUid = null
                    if (vm.newShots.any { it.uid == uid }) vm.dismissShotReview()
                },
                onReset = { vm.resetReviewedTiming(uid) },
            )
        } ?: run { waveformReviewUid = null }
    }

    if (manualEntry) {
        ManualEntryDialog(
            vm = vm,
            onDismiss = { manualEntry = false },
            onSave = { label, shotType, tool, target, tdVal, tdUnit, loading, projectile, passFail, notes, vel, velFps, epoch, photos ->
                vm.addManualEntry(
                    label, shotType, tool, loading, projectile, target, tdVal, tdUnit,
                    passFail, notes, vel, velFps, epoch, photos,
                )
                manualEntry = false
            },
        )
    }

    if (showLowBattery) {
        AlertDialog(
            onDismissRequest = { dismissedLowBatteryMv = batteryMv },
            title = { Text("Low logger battery") },
            text = {
                Text(
                    "The connected logger battery is low. Recharge or replace it before relying on another shot.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = { dismissedLowBatteryMv = batteryMv }) {
                    Text("OK")
                }
            },
        )
    }

    // Photo prompts: after setup, and after each recorded shot.
    vm.photoPrompt?.let { kind ->
        val promptPhotos = remember(photoRevision, kind, vm.pendingLabel, vm.isSimulation) { vm.promptPhotos(kind) }
        val savedCount = promptPhotos.size
        AlertDialog(
            onDismissRequest = { vm.dismissPhotoPrompt() },
            title = { Text(if (kind == "setup") "Setup photos" else "After photos") },
            text = {
                Column {
                    Text(
                        if (kind == "setup")
                            "Capture setup angles before the shot."
                        else
                            "Capture target condition after the shot.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (savedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$savedCount photo${if (savedCount == 1) "" else "s"} saved",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Good,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(promptPhotos) { uri ->
                                PhotoThumbnail(
                                    uri = uri,
                                    selected = selectedPromptPhoto == uri,
                                    selectionMode = selectedPromptPhoto != null,
                                    onOpen = { promptPhotoPreview = uri },
                                    onSelect = {
                                        selectedPromptPhoto = if (selectedPromptPhoto == uri) null else uri
                                    },
                                )
                            }
                        }
                        if (selectedPromptPhoto != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                selectedPromptPhoto?.let { vm.deletePromptPhoto(it) }
                                selectedPromptPhoto = null
                            }) {
                                Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Delete selected", color = Bad)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { pickPromptPhotos.launch("image/*") }) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Upload")
                    }
                    TextButton(onClick = {
                        vm.newPhotoUri()?.let { uri ->
                            pendingPhoto = uri
                            runCatching { takePicture.launch(uri) }
                        }
                    }) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Take Photo")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPhotoPrompt() }) {
                    Text(if (savedCount == 0) "Skip" else "Done")
                }
            },
        )
    }

    promptPhotoPreview?.let { uri ->
        Dialog(
            onDismissRequest = { promptPhotoPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { promptPhotoPreview = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Tap image to close",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupPhotoStrip(
    photos: List<android.net.Uri>,
    selected: android.net.Uri?,
    onOpen: (android.net.Uri) -> Unit,
    onSelect: (android.net.Uri) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Setup photos",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.weight(1f),
            )
            if (selected != null) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Delete", color = Bad)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos) { uri ->
                PhotoThumbnail(
                    uri = uri,
                    selected = selected == uri,
                    selectionMode = selected != null,
                    onOpen = { onOpen(uri) },
                    onSelect = { onSelect(uri) },
                )
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    uri: android.net.Uri,
    selected: Boolean,
    selectionMode: Boolean = false,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = uri,
        contentDescription = "saved photo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) Bad else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .pointerInput(uri, selected, selectionMode) {
                detectTapGestures(
                    onTap = { if (selected || selectionMode) onSelect() else onOpen() },
                    onLongPress = { onSelect() },
                )
            },
    )
}

@Composable
private fun FullLogDialog(
    vm: ChronoViewModel,
    onExit: () -> Unit,
    onEdit: (TestResult) -> Unit,
    onOpenPhoto: (android.net.Uri, String) -> Unit,
) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Test log", style = MaterialTheme.typography.headlineMedium, color = Amber)
                    Text(
                        "${vm.results.size} result${if (vm.results.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                }
                TextButton(onClick = onExit) { Text("Exit") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.results, key = { it.uid }) { r ->
                    Column {
                        ResultCard(
                            r = r,
                            latest = r == vm.results.firstOrNull(),
                            accuracyEnvelopePercent = vm.accuracyEnvelopePercentFor(r),
                            coverFor = { vm.photosFor(r) },
                            onEdit = { onEdit(r) },
                            onOpenPhoto = { onOpenPhoto(it, r.uid) },
                        )
                        val photos = vm.photosFor(r)
                        if (photos.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(photos) { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "shot photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onOpenPhoto(uri, r.uid) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 390.dp
        val titleStyle = MaterialTheme.typography.headlineMedium.copy(
            fontSize = if (compact) 24.sp else 29.sp,
            lineHeight = if (compact) 28.sp else 34.sp,
        )
        val statusStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = if (compact) 15.sp else 17.sp,
            lineHeight = if (compact) 20.sp else 23.sp,
        )

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "JERAL INNOVATIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "CHRONO LOGGER",
                            style = titleStyle,
                            color = Amber,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (vm.isSimulation) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "SIM",
                                style = MaterialTheme.typography.labelSmall,
                                color = Teal,
                                modifier = Modifier
                                    .background(Teal.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { vm.disconnectOrExit() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(
                        if (connState == ConnState.DISCONNECTED) "Exit" else "Disconnect",
                        style = statusStyle,
                        color = TextDim,
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint, label) = when (connState) {
                    ConnState.CONNECTED ->
                        Triple(Icons.Filled.BluetoothConnected, Good, "Connected")
                    ConnState.RECONNECTING ->
                        Triple(Icons.Filled.BluetoothDisabled, Bad, "Reconnecting")
                    else -> Triple(Icons.Filled.BluetoothDisabled, TextDim, "Disconnected")
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(5.dp))
                Text(label, style = statusStyle, color = tint, maxLines = 1)
                Spacer(Modifier.size(if (compact) 10.dp else 14.dp))
                val timeOk = deviceStatus?.timeValid == true
                Icon(
                    Icons.Filled.AccessTime, null,
                    tint = if (timeOk) Teal else TextDim,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { vm.syncTime() },
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    if (timeOk) "Clock synced" else "Clock not set",
                    style = statusStyle,
                    color = if (timeOk) Teal else TextDim,
                    maxLines = 1,
                    modifier = Modifier.clickable { vm.syncTime() },
                )
                BatteryIndicator(deviceStatus, compact = compact)
            }
            vm.projectName?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${vm.session.pathLabel} - tap to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { vm.openDataFolder() },
                )
            }
        }
    }
}
@Composable
private fun BatteryIndicator(deviceStatus: DeviceStatus?, compact: Boolean = false) {
    val percent = deviceStatus?.batteryPercent?.coerceIn(0, 100)
    val mv = deviceStatus?.batteryMv
    if (percent == null && mv == null) return

    val tint = when {
        deviceStatus?.lowBattery == true -> Bad
        percent != null && percent <= 35 -> Amber
        else -> Good
    }
    val label = buildString {
        if (percent != null) append("$percent%")
        if (mv != null) {
            if (isNotEmpty()) append(" ")
            append(String.format("%.2fV", mv / 1000.0))
        }
        if (deviceStatus?.batteryLocked == true) {
            if (isNotEmpty()) append(" ")
            append("ARM LOCK")
        }
    }

    Spacer(Modifier.size(if (compact) 8.dp else 14.dp))
    Canvas(modifier = Modifier.size(width = if (compact) 22.dp else 28.dp, height = 14.dp)) {
        val stroke = 1.4.dp.toPx()
        val bodyWidth = size.width - 4.dp.toPx()
        drawRoundRect(
            color = tint,
            size = androidx.compose.ui.geometry.Size(bodyWidth, size.height),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * 0.32f),
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height * 0.36f),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
        )
        val fillFraction = ((percent ?: batteryPercentFromMv(mv ?: 0)).coerceIn(0, 100)) / 100f
        drawRoundRect(
            color = tint.copy(alpha = 0.85f),
            topLeft = Offset(stroke * 2f, stroke * 2f),
            size = androidx.compose.ui.geometry.Size(
                ((bodyWidth - stroke * 4f) * fillFraction).coerceAtLeast(1.dp.toPx()),
                (size.height - stroke * 4f).coerceAtLeast(1.dp.toPx()),
            ),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
    }
    Spacer(Modifier.size(5.dp))
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (compact) 15.sp else 17.sp),
        color = tint,
        maxLines = 1,
    )
}

private fun batteryPercentFromMv(mv: Int): Int {
    if (mv >= 4200) return 100
    if (mv <= 3300) return 0
    return (((mv - 3300) * 100.0) / 900.0).roundToInt().coerceIn(0, 100)
}

@Composable
private fun SimulationFaultControl(vm: ChronoViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Simulated fault: ${vm.ble.simFault.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SimFault.entries.forEach { fault ->
                DropdownMenuItem(
                    text = { Text(fault.label) },
                    onClick = { vm.setSimFault(fault); open = false },
                )
            }
        }
    }
}

@Composable
private fun PortHealthCard(
    health: HealthStatus?,
    checking: Boolean,
    enabled: Boolean,
    onCheck: () -> Unit,
    onIdentify: () -> Unit,
    onOverride: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PORT HEALTH", style = MaterialTheme.typography.labelSmall,
                    color = TextDim, modifier = Modifier.weight(1f))
                TextButton(onClick = onIdentify, enabled = enabled) { Text("Identify") }
                TextButton(onClick = onCheck, enabled = enabled && !checking) {
                    Text(if (checking) "Checking..." else "Check")
                }
            }
            if (health == null || health.checkedAtBootMs == 0L) {
                Text("Run a check before arming.", color = TextDim)
            } else {
                val rows = listOf(1 to health.channel1, 2 to health.channel2)
                rows.forEach { (channel, port) ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("CH$channel", modifier = Modifier.weight(0.25f), color = TextDim)
                        Text(
                            port.summary(),
                            modifier = Modifier.weight(0.75f),
                            color = when {
                                port.serious -> Bad
                                port.flags != 0 -> Amber
                                else -> Good
                            },
                        )
                    }
                }
                if (!health.ready) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Arming is blocked. Leakage faults are reported conservatively; " +
                            "software cannot prove that moisture is the cause.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOverride, enabled = enabled) {
                        Text("Arm with logged override", color = Amber)
                    }
                }
            }
        }
    }
}

/**
 * The rig, drawn the way it stands on the bench: sensor 1 up front, the
 * measured gap, sensor 2 behind it. Tap a sensor to retest/replace it; tap
 * the distance to change it. Screens consumed by a shot show torn + amber.
 */
@Composable
private fun RigCard(vm: ChronoViewModel, enabled: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                "SHOT DIRECTION  ▸",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SensorGraphic(1, vm.sensor1Ready, enabled) { vm.startRetest(1) }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpanArrow()
                    TextButton(onClick = { vm.changeDistance() }, enabled = enabled) {
                        Text(
                            "${trimZeros(vm.distanceInUnit())} ${vm.distanceUnit.label}",
                            color = Teal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                    )
                    vm.estimatedAccuracyEnvelopeAtCurrentSpacing()?.let { ci ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "about +/- %.1f%% GAE here".format(ci),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ci > 2.0) Amber else TextDim,
                            )
                            Spacer(Modifier.size(6.dp))
                            InfoDot(
                                "GAE",
                                "GAE means Guaranteed Accuracy Envelope. It is the app's conservative +/- percent range around the measured velocity. For example, 1000 ft/s with +/- 2% GAE means the measurement should be treated as about 980 to 1020 ft/s. The app builds that percent from timer resolution, clock drift, edge jitter, sensor spacing uncertainty, and calibration data. Faster shots and shorter sensor spacing widen the envelope because the same timing error is a larger share of the measurement. Closely matched channel calibration narrows it.",
                            )
                        }
                    }
                }
                SensorGraphic(2, vm.sensor2Ready, enabled) { vm.startRetest(2) }
            }
            if (!vm.sensor1Ready || !vm.sensor2Ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Each shot consumes the screens. Tap a torn sensor to connect " +
                        "and verify its replacement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun SensorGraphic(number: Int, ready: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val frame = if (ready) Good else Amber
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp, 80.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(color = PanelHigh, cornerRadius = CornerRadius(10f, 10f))
                // screen mesh
                for (i in 1..3) {
                    val x = w * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 6f), Offset(x, h - 6f), 2f)
                    val y = h * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(6f, y), Offset(w - 6f, y), 2f)
                }
                drawRoundRect(
                    color = frame,
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 5f),
                )
                if (!ready) {
                    // jagged tear through a consumed screen
                    val tear = Path().apply {
                        moveTo(w * 0.55f, 0f)
                        lineTo(w * 0.35f, h * 0.30f)
                        lineTo(w * 0.62f, h * 0.46f)
                        lineTo(w * 0.40f, h * 0.72f)
                        lineTo(w * 0.55f, h)
                    }
                    drawPath(tear, Amber, style = Stroke(width = 4f))
                }
            }
            Text(
                "$number",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = frame,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (number == 1) "START" else "STOP",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Text(
            if (ready) "Ready" else "Connect new\nsensor",
            style = MaterialTheme.typography.bodyMedium,
            color = frame,
            textAlign = TextAlign.Center,
        )
    }
}

/** A |◀——▶| measurement arrow spanning the gap between the two screens. */
@Composable
private fun SpanArrow() {
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val cy = size.height / 2f
        drawLine(TextDim, Offset(2f, 3f), Offset(2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(w - 2f, 3f), Offset(w - 2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(w - 2f, cy), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy + 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy + 5f), 3f)
    }
}

@Composable
private fun ChannelsCard(vm: ChronoViewModel, enabled: Boolean) {
    val load1 = vm.channelLoadNs(1)
    val load2 = vm.channelLoadNs(2)
    val mismatch = vm.channelMismatchNs()
    val mismatchPercent = vm.channelMismatchPercent()
    val hasBaseline = vm.calData.containsKey("b1") && vm.calData.containsKey("b2")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CHANNEL CALIBRATION",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                InfoDot(
                    "CHANNEL CALIBRATION",
                    "Measures each loaded sensor's RC trigger delay against the empty-port baseline. Closely matched RC signatures reduce timing uncertainty in the GAE calculation.",
                )
                Spacer(Modifier.size(8.dp))
                if (vm.calRunning) {
                    CircularProgressIndicator(
                        color = Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    )
                } else {
                    OutlinedButton(
                        onClick = { vm.recalibrateChannels() },
                        enabled = enabled && hasBaseline,
                    ) { Text("Recheck") }
                }
            }
            Spacer(Modifier.height(10.dp))
            for ((ch, load) in listOf(1 to load1, 2 to load2)) {
                Text(
                    if (load != null)
                        "Port $ch RC delay: +${vm.rcDelayText(load)} over baseline"
                    else "Port $ch RC delay: not measured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when {
                !hasBaseline -> Text(
                    "No baseline captured - redo setup with empty ports to enable.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch == null -> Text(
                    "Plug in and measure both sensors to compare channels.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch < 600 -> Text(
                    "Channels matched (delta ${vm.rcDelayText(mismatch)} - timing impact negligible)",
                    style = MaterialTheme.typography.bodyMedium, color = Good,
                )
                else -> Text(
                    "Channel RC mismatch: delta ${vm.rcDelayText(mismatch)}" +
                        (mismatchPercent?.let { " (${String.format("%.1f", it)}% of average)" } ?: "") +
                        " - match cable/sensor assemblies when possible.",
                    style = MaterialTheme.typography.bodyMedium, color = Amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            val hw by vm.ble.hwInfo.collectAsState()
            Text(
                hw?.let {
                    val serial = it.mcuSerial.ifBlank { "serial unavailable" }
                    "Hardware rev ${it.hwRev} - fw ${it.fwMajor}.${it.fwMinor} - " +
                        "%.1f ns timer - $serial".format(it.tickPs / 1000.0)
                } ?: "Hardware not identified - assuming rev 1 accuracy",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
        }
    }
}

@Composable
private fun NextTestCard(vm: ChronoViewModel, armed: Boolean) {
    val fieldText = MaterialTheme.typography.bodyMedium
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("Next test", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Saved with the next shot. Editable later.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = vm.pendingLabel,
                onValueChange = { vm.pendingLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("LABEL", "Short name for this shot, such as Test 1 or 20 degree upward angle.") },
                placeholder = { Text("Test 1", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            ShotTypeSelector(vm.pendingShotType) { vm.pendingShotType = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTool,
                onValueChange = { vm.pendingTool = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("DISRUPTOR TYPE/MODEL", "Manufacturer and model or locally used identifier for the disruptor.") },
                placeholder = { Text("Manufacturer/model or local ID", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingDisruptorLoading,
                onValueChange = { vm.pendingDisruptorLoading = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("DISRUPTOR LOADING", "Load or charge configuration used for this shot.") },
                placeholder = { Text("BK40, C2, water load, etc.", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingProjectileType,
                onValueChange = { vm.pendingProjectileType = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("PROJECTILE TYPE", "Material launched by the disruptor. Water is the default.") },
                placeholder = { Text("Water", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTarget,
                onValueChange = { vm.pendingTarget = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("TARGET", "Target item or test article being engaged.") },
                placeholder = { Text("Ammo Can etc.", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTargetDistVal,
                onValueChange = { vm.pendingTargetDistVal = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldLabel("STAND-OFF DISTANCE", "Distance from the disruptor muzzle/reference point to the target.") },
                placeholder = { Text("18", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    UnitSelector(vm.pendingTargetDistUnit) { vm.pendingTargetDistUnit = it }
                },
            )
        }
    }
}

@Composable
private fun ArmButton(
    armed: Boolean,
    running: Boolean,
    connected: Boolean,
    sensorsReady: Boolean,
    batteryLocked: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "arm")
    val pulse by t.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "armPulse",
    )

    when {
        running -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .border(2.dp, Color(0xFFE00000), RoundedCornerShape(20.dp))
                    .background(
                        Color(0xFFE00000).copy(alpha = 0.18f + 0.20f * pulse),
                        RoundedCornerShape(20.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "RECORDING - SHOT IN FLIGHT",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        armed -> {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .border(2.dp, Color(0xFFE00000), RoundedCornerShape(20.dp))
                        .background(
                            Color(0xFFE00000).copy(alpha = 0.14f + 0.24f * pulse),
                            RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "STANDBY - WAITING FOR SHOT",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You can walk away; the result uploads when you're back in range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                    Text("Disarm")
                }
            }
        }
        else -> {
            Column {
                Button(
                    onClick = onArm,
                    enabled = connected && sensorsReady,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE00000),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE00000).copy(alpha = 0.28f),
                        disabledContentColor = Color.White.copy(alpha = 0.55f),
                    ),
                ) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(Color.White)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("RECORD", style = MaterialTheme.typography.labelLarge)
                }
                if (connected && batteryLocked) {
                    Text(
                        "Battery is too low to arm. Charge it above the recovery threshold.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Bad,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else if (connected && !sensorsReady) {
                    Text(
                        "Replace and retest both sensors before recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    r: TestResult,
    latest: Boolean,
    accuracyEnvelopePercent: Double,
    coverFor: () -> List<android.net.Uri>,
    onEdit: () -> Unit,
    onOpenPhoto: (android.net.Uri) -> Unit,
    onReviewWaveform: (() -> Unit)? = null,
) {
    // Resolve the cover image off the main thread: chosen thumbnail, else first photo.
    val cover by androidx.compose.runtime.produceState<android.net.Uri?>(
        null, r.uid, r.thumbnailUri, r.shotFolder,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            r.thumbnailUri.takeIf { it.isNotBlank() }
                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                ?: coverFor().firstOrNull()
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (latest) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.label.ifBlank { "Untitled test" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.label.isBlank()) TextDim else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, "Edit", tint = TextDim, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (r.metersPerSecond != 0.0) "%.1f".format(r.feetPerSecond) else "—",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                        color = Amber,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("ft/s", color = TextDim, modifier = Modifier.padding(bottom = 5.dp))
                }
                Spacer(Modifier.height(4.dp))
                val envelopeText = if (accuracyEnvelopePercent >= 0.05)
                    "+/- %.1f%% GAE".format(accuracyEnvelopePercent) else "+/- <0.1% GAE"
                val detail = when {
                    r.isManual && r.metersPerSecond != 0.0 ->
                        "%.2f m/s  ·  manual entry".format(r.metersPerSecond)
                    r.isManual -> "manual entry"
                    else -> "%.2f m/s  -  %s  -  %s".format(
                        r.metersPerSecond, r.splitTimeText(), envelopeText
                    )
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.isManual) Teal else TextDim,
                )
                if (r.hasWaveform && onReviewWaveform != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onReviewWaveform) {
                        Icon(Icons.Filled.AccessTime, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (r.isWaveformReviewed) "Reviewed waveform" else "Review waveform")
                    }
                }
                r.timingFaultText()?.let { fault ->
                    Spacer(Modifier.height(6.dp))
                    Text(fault, style = MaterialTheme.typography.bodyMedium, color = Bad)
                }
                val meta = listOfNotNull(
                    r.shotType.ifBlank { "Standard" },
                    r.tool.takeIf { it.isNotBlank() }?.let { "Disruptor: $it" },
                    r.disruptorLoading.takeIf { it.isNotBlank() }?.let { "Loading: $it" },
                    r.projectileType.ifBlank { "Water" }.let { "Projectile: $it" },
                    r.target.takeIf { it.isNotBlank() }?.let { "Target: $it" },
                    r.targetDistanceText()?.let { "@ $it" },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                if (r.passFail.isNotBlank() || r.specialNotes.isNotBlank() || r.outcome.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            r.passFail.takeIf { it.isNotBlank() },
                            r.specialNotes.ifBlank { r.outcome }.takeIf { it.isNotBlank() },
                        ).joinToString(" - "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val date = r.formattedDate()
                Text(
                    date ?: "No date — tap ✎ to set one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (date != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    else TextDim.copy(alpha = 0.55f),
                )
            }
            cover?.let { uri ->
                Spacer(Modifier.size(14.dp))
                AsyncImage(
                    model = uri,
                    contentDescription = "shot photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenPhoto(uri) },
                )
            }
        }
    }
}

private val EDIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun InfoDot(title: String, help: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(18.dp)
            .border(1.dp, Teal, RoundedCornerShape(9.dp))
            .clickable { open = true },
    ) {
        Text("i", color = Teal, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Text(
                    help,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun FieldLabel(label: String, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (help != null) {
            Spacer(Modifier.size(6.dp))
            InfoDot(label, help)
        }
    }
}

@Composable
private fun ChoiceSelector(
    label: String,
    value: String,
    options: List<String>,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    FieldLabel(label, help)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            val selected = value.equals(option, ignoreCase = true)
            if (selected) {
                Button(
                    onClick = { onChange(option) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        option,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { onChange(option) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        option,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Compact tap-to-open unit picker, used as a trailing icon inside fields. */
@Composable
private fun UnitSelector(unit: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            unit,
            color = Teal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TARGET_DIST_UNITS.forEach { u ->
                DropdownMenuItem(text = { Text(u) }, onClick = { onSelect(u); open = false })
            }
        }
    }
}

/**
 * Date/time entry that helps instead of nagging: clicking the empty box
 * autofills the current date and pre-selects the time digits so the user can
 * immediately type over them.
 */
@Composable
private fun DateTimeField(
    field: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    isError: Boolean,
    supporting: String?,
) {
    OutlinedTextField(
        value = field,
        onValueChange = onChange,
        label = { FieldLabel("DATE & TIME", "Shot date and local time used in the log and export.") },
        placeholder = { Text("tap to fill current time", color = TextDim) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        isError = isError,
        supportingText = {
            if (isError) Text("Use format 2026-07-05 14:30")
            else if (supporting != null) Text(supporting, color = TextDim)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { st ->
                if (st.isFocused && field.text.isBlank()) {
                    val now = EDIT_DATE_FORMAT.format(LocalDateTime.now())
                    onChange(TextFieldValue(now, selection = TextRange(11, 16)))
                }
            },
    )
}

private fun parseDateField(text: String): Long? =
    text.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching {
            LocalDateTime.parse(it, EDIT_DATE_FORMAT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

@Composable
private fun ReadOnlyLogValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PassFailSelector(value: String, onChange: (String) -> Unit) {
    ChoiceSelector(
        label = "PASS/FAIL",
        value = value,
        options = listOf("Pass", "Fail"),
        help = "Primary success metric for the shot. Use Pass when the shot met the intended test objective.",
        onChange = onChange,
    )
}

@Composable
private fun ShotTypeSelector(value: String, onChange: (String) -> Unit) {
    ChoiceSelector(
        label = "SHOT TYPE",
        value = value.ifBlank { "Standard" },
        options = listOf("Standard", "Experimental"),
        help = "Standard means the shot follows FBI or manufacturer guidance. Experimental means any setup outside those recommendations.",
        onChange = onChange,
    )
}

@Composable
private fun EditResultDialog(
    result: TestResult,
    title: String = "Edit test",
    dismissText: String = "Cancel",
    showDelete: Boolean = true,
    showRecordedValues: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (
        label: String, shotType: String, tool: String, target: String,
        targetDistValue: Double?, targetDistUnit: String,
        disruptorLoading: String, projectileType: String,
        passFail: String, specialNotes: String, epochMillis: Long?,
    ) -> Unit,
    onAttachPhotos: (List<android.net.Uri>) -> Unit,
    photosFor: () -> List<android.net.Uri>,
    onOpenPhoto: (android.net.Uri) -> Unit,
    onDeletePhoto: (android.net.Uri) -> Unit,
    onDelete: () -> Unit,
) {
    var photoRefresh by remember { mutableStateOf(0) }
    val photos = remember(photoRefresh) { photosFor() }
    var selectedPhoto by remember(photoRefresh) { mutableStateOf<android.net.Uri?>(null) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttachPhotos(uris)
            photoRefresh++
        }
    }
    var label by remember { mutableStateOf(result.label) }
    var shotType by remember { mutableStateOf(result.shotType.ifBlank { "Standard" }) }
    var tool by remember { mutableStateOf(result.tool) }
    var disruptorLoading by remember { mutableStateOf(result.disruptorLoading) }
    var projectileType by remember { mutableStateOf(result.projectileType.ifBlank { "Water" }) }
    var target by remember { mutableStateOf(result.target) }
    var tdVal by remember { mutableStateOf(result.targetDistValue?.let { trimZeros(it) } ?: "") }
    var tdUnit by remember { mutableStateOf(result.targetDistUnit) }
    var passFail by remember { mutableStateOf(result.passFail) }
    var specialNotes by remember { mutableStateOf(result.specialNotes.ifBlank { result.outcome }) }
    var dateField by remember {
        mutableStateOf(
            TextFieldValue(
                result.epochMillis?.let {
                    EDIT_DATE_FORMAT.format(
                        LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()
                        )
                    )
                } ?: ""
            )
        )
    }
    val parsedDate = parseDateField(dateField.text)
    val dateError = dateField.text.isNotBlank() && parsedDate == null
    val fieldText = MaterialTheme.typography.bodyMedium

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (showRecordedValues) {
                    val spacingIn = result.distanceM * 39.3701
                    val velocityText = if (result.metersPerSecond != 0.0)
                        "%.1f ft/s".format(result.feetPerSecond) else "Not recorded"
                    ReadOnlyLogValue("Velocity", velocityText)
                    ReadOnlyLogValue("SENSOR SPACING", "%.3f in".format(spacingIn))
                    ReadOnlyLogValue("Split time", result.splitTimeText())
                    if (result.deviceSerial.isNotBlank()) {
                        ReadOnlyLogValue("MCU serial", result.deviceSerial)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { FieldLabel("LABEL", "Short name for this shot.") },
                    placeholder = { Text("Test 1", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ShotTypeSelector(shotType) { shotType = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tool,
                    onValueChange = { tool = it },
                    label = { FieldLabel("DISRUPTOR TYPE/MODEL", "Manufacturer and model or locally used identifier for the disruptor.") },
                    placeholder = { Text("Manufacturer/model or local ID", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = disruptorLoading,
                    onValueChange = { disruptorLoading = it },
                    label = { FieldLabel("DISRUPTOR LOADING", "Load or charge configuration used for this shot.") },
                    placeholder = { Text("BK40, C2, water load, etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = projectileType,
                    onValueChange = { projectileType = it },
                    label = { FieldLabel("PROJECTILE TYPE", "Material launched by the disruptor. Water is the default.") },
                    placeholder = { Text("Water", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { FieldLabel("TARGET", "Target item or test article being engaged.") },
                    placeholder = { Text("Ammo Can etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tdVal,
                    onValueChange = { tdVal = it },
                    label = { FieldLabel("STAND-OFF DISTANCE", "Distance from the disruptor muzzle/reference point to the target.") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { UnitSelector(tdUnit) { tdUnit = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PassFailSelector(passFail) { passFail = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = specialNotes,
                    onValueChange = { specialNotes = it },
                    label = { FieldLabel("SPECIAL NOTES", "Anything useful for later review: setup deviations, target condition, observed effect, or photo context.") },
                    placeholder = { Text("penetration, depth, dent etc.", color = TextDim) },
                    textStyle = fieldText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (showRecordedValues && specialNotes.isBlank()) Amber.copy(alpha = 0.14f)
                            else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        ),
                )
                Spacer(Modifier.height(8.dp))
                DateTimeField(
                    field = dateField,
                    onChange = { dateField = it },
                    isError = dateError,
                    supporting = if (result.epochMillis == null && dateField.text.isBlank())
                        "Device clock wasn't synced for this test" else null,
                )
                if (photos.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Photos", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos) { uri ->
                            PhotoThumbnail(
                                uri = uri,
                                selected = selectedPhoto == uri,
                                selectionMode = selectedPhoto != null,
                                onOpen = { onOpenPhoto(uri) },
                                onSelect = { selectedPhoto = if (selectedPhoto == uri) null else uri },
                            )
                        }
                    }
                    if (selectedPhoto != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            selectedPhoto?.let { onDeletePhoto(it) }
                            selectedPhoto = null
                            photoRefresh++
                        }) {
                            Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Delete selected photo", color = Bad)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add photos", color = Teal)
                }
                if (showDelete) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Delete this result", color = Bad)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(), shotType.trim().ifBlank { "Standard" }, tool.trim(), target.trim(),
                        tdVal.replace(',', '.').toDoubleOrNull(), tdUnit,
                        disruptorLoading.trim(), projectileType.trim().ifBlank { "Water" },
                        passFail.trim(), specialNotes.trim(), parsedDate,
                    )
                },
                enabled = !dateError,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

@Composable
private fun ManualEntryDialog(
    vm: ChronoViewModel,
    onDismiss: () -> Unit,
    onSave: (
        label: String, shotType: String, tool: String, target: String,
        targetDistValue: Double?, targetDistUnit: String,
        disruptorLoading: String, projectileType: String,
        passFail: String, specialNotes: String,
        velocity: Double?, velocityIsFps: Boolean, epochMillis: Long?,
        photos: List<android.net.Uri>,
    ) -> Unit,
) {
    var pickedPhotos by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> pickedPhotos = pickedPhotos + uris }
    var label by remember { mutableStateOf(vm.pendingLabel) }
    var shotType by remember { mutableStateOf(vm.pendingShotType.ifBlank { "Standard" }) }
    var tool by remember { mutableStateOf(vm.pendingTool) }
    var disruptorLoading by remember { mutableStateOf(vm.pendingDisruptorLoading) }
    var projectileType by remember { mutableStateOf(vm.pendingProjectileType.ifBlank { "Water" }) }
    var target by remember { mutableStateOf(vm.pendingTarget) }
    var tdVal by remember { mutableStateOf(vm.pendingTargetDistVal) }
    var tdUnit by remember { mutableStateOf(vm.pendingTargetDistUnit) }
    var passFail by remember { mutableStateOf("") }
    var specialNotes by remember { mutableStateOf("") }
    var velText by remember { mutableStateOf("") }
    var velIsFps by remember { mutableStateOf(true) }
    var velUnitOpen by remember { mutableStateOf(false) }
    var dateField by remember {
        mutableStateOf(
            TextFieldValue(
                EDIT_DATE_FORMAT.format(LocalDateTime.now()),
                selection = TextRange(11, 16),
            )
        )
    }
    val parsedDate = parseDateField(dateField.text)
    val dateError = dateField.text.isNotBlank() && parsedDate == null
    val fieldText = MaterialTheme.typography.bodyMedium

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Log a shot without a connected device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = velText,
                    onValueChange = { velText = it },
                    label = { FieldLabel("VELOCITY (OPTIONAL)", "Known or manually measured projectile velocity. Leave blank for notes/photos only.") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        Box {
                            TextButton(onClick = { velUnitOpen = true }) {
                                Text(if (velIsFps) "ft/s" else "m/s", color = Teal)
                            }
                            DropdownMenu(
                                expanded = velUnitOpen,
                                onDismissRequest = { velUnitOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text("ft/s") },
                                    onClick = { velIsFps = true; velUnitOpen = false })
                                DropdownMenuItem(text = { Text("m/s") },
                                    onClick = { velIsFps = false; velUnitOpen = false })
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { FieldLabel("LABEL", "Short name for this shot.") },
                    placeholder = { Text("Test 1", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ShotTypeSelector(shotType) { shotType = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tool,
                    onValueChange = { tool = it },
                    label = { FieldLabel("DISRUPTOR TYPE/MODEL", "Manufacturer and model or locally used identifier for the disruptor.") },
                    placeholder = { Text("Manufacturer/model or local ID", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = disruptorLoading,
                    onValueChange = { disruptorLoading = it },
                    label = { FieldLabel("DISRUPTOR LOADING", "Load or charge configuration used for this shot.") },
                    placeholder = { Text("BK40, C2, water load, etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = projectileType,
                    onValueChange = { projectileType = it },
                    label = { FieldLabel("PROJECTILE TYPE", "Material launched by the disruptor. Water is the default.") },
                    placeholder = { Text("Water", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { FieldLabel("TARGET", "Target item or test article being engaged.") },
                    placeholder = { Text("Ammo Can etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tdVal,
                    onValueChange = { tdVal = it },
                    label = { FieldLabel("STAND-OFF DISTANCE", "Distance from the disruptor muzzle/reference point to the target.") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { UnitSelector(tdUnit) { tdUnit = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PassFailSelector(passFail) { passFail = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = specialNotes,
                    onValueChange = { specialNotes = it },
                    label = { FieldLabel("SPECIAL NOTES", "Anything useful for later review: setup deviations, target condition, observed effect, or photo context.") },
                    placeholder = { Text("penetration, depth, dent etc.", color = TextDim) },
                    textStyle = fieldText,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                DateTimeField(
                    field = dateField,
                    onChange = { dateField = it },
                    isError = dateError,
                    supporting = null,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (pickedPhotos.isEmpty()) "Add photos"
                        else "Add photos (${pickedPhotos.size} selected)",
                        color = Teal,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(), shotType.trim().ifBlank { "Standard" }, tool.trim(), target.trim(),
                        tdVal.replace(',', '.').toDoubleOrNull(), tdUnit,
                        disruptorLoading.trim(), projectileType.trim().ifBlank { "Water" },
                        passFail.trim(), specialNotes.trim(),
                        velText.replace(',', '.').toDoubleOrNull(), velIsFps, parsedDate,
                        pickedPhotos,
                    )
                },
                enabled = !dateError,
            ) { Text("Save entry") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
