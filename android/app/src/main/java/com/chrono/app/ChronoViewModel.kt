package com.chrono.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrono.app.ble.CalReading
import com.chrono.app.ble.ChronoBle
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.Proto
import com.chrono.app.ble.RawResult
import com.chrono.app.ble.RawTrace
import android.net.Uri
import com.chrono.app.data.DistanceUnit
import com.chrono.app.data.ResultStore
import com.chrono.app.data.SessionManager
import com.chrono.app.data.TestResult
import com.chrono.app.data.WaveformCodec
import com.chrono.app.data.ticksToNanoseconds
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.chrono.app.ble.HwInfo
import com.chrono.app.ble.SimFault
import com.chrono.app.ble.HealthStatus
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class Screen { CONNECT, BASELINE, SENSOR1, SENSOR2, DISTANCE, DASHBOARD }

enum class CalPhase { BARE, LOADED }

/** Stored summary of one calibration sweep (times in ns). */
data class CalEntry(
    val medianNs: Long,
    val stddevNs: Long,
    val samples: Int,
    val status: Int,
    val at: Long,
) {
    val isUsable: Boolean get() = status != 2 && samples > 0 &&
        System.currentTimeMillis() - at <= 30L * 24L * 60L * 60L * 1000L
}

class ChronoViewModel(app: Application) : AndroidViewModel(app) {

    val ble = ChronoBle(app)
    private val realStore = ResultStore(app, simulation = false)
    private val simStore = ResultStore(app, simulation = true)
    private val store: ResultStore get() = if (ble.isSimulation) simStore else realStore
    private val prefs = app.getSharedPreferences("chrono", Application.MODE_PRIVATE)
    private val realSession = SessionManager(app, simulation = false)
    private val simSession = SessionManager(app, simulation = true)
    /** Active data sink; simulated runs are fully isolated from real ones. */
    val session: SessionManager get() = if (ble.isSimulation) simSession else realSession

    var screen by mutableStateOf(Screen.CONNECT)
        private set

    val results = mutableStateListOf<TestResult>()

    /** Details applied to the next test; all editable on the result afterwards.
     *  Shot setup fields persist across sessions (they rarely change mid-range-day). */
    // Auto-fills Test1, Test2, … per project; the user can override it.
    var pendingLabel by mutableStateOf(session.suggestedLabel())
    var pendingShotType by mutableStateOf(prefs.getString("pendShotType", "Standard") ?: "Standard")
    var pendingTool by mutableStateOf(prefs.getString("pendTool", "") ?: "")
    var pendingDisruptorLoading by mutableStateOf(prefs.getString("pendLoading", "") ?: "")
    var pendingProjectileType by mutableStateOf(prefs.getString("pendProjectile", "Water") ?: "Water")
    var pendingTarget by mutableStateOf(prefs.getString("pendTarget", "") ?: "")
    var pendingTargetDistVal by mutableStateOf(prefs.getString("pendTdVal", "") ?: "")
    var pendingTargetDistUnit by mutableStateOf(prefs.getString("pendTdUnit", "in") ?: "in")

    // ------------------------------------------------- project folder & photos

    /** Shown only on a genuinely new day (or first run): name a new project
     *  folder or keep logging into the previous one. */
    var projectPrompt by mutableStateOf(session.needsProjectPrompt())
        private set
    val projectName: String? get() = session.projectName
    fun defaultProjectName(): String = session.today()

    fun startProject(name: String) {
        session.startProject(name)
        pendingLabel = session.suggestedLabel()
        projectPrompt = false
    }
    fun keepProject() {
        session.continueProject()
        pendingLabel = session.suggestedLabel()
        projectPrompt = false
    }

    /** "setup" or "after" while the photo dialog is showing. */
    var photoPrompt by mutableStateOf<String?>(null)
        private set
    var photoCount by mutableStateOf(0)
        private set
    var photoRevision by mutableStateOf(0)
        private set
    var pendingCameraUri by mutableStateOf(
        prefs.getString("pendingCameraUri", null)?.let(Uri::parse)
    )
        private set
    var photoPromptResultUid by mutableStateOf(
        prefs.getString("photoPromptResultUid", null)
    )
        private set
    var setupPhotosNeeded by mutableStateOf(prefs.getBoolean("setupPhotosNeeded", false))
        private set
    var resultPromptUid by mutableStateOf<String?>(null)
        private set
    var showFullLog by mutableStateOf(false)
        private set
    val resultPrompt: TestResult?
        get() = resultPromptUid?.let { uid -> results.firstOrNull { it.uid == uid } }

    // photoPrompt is persisted so a camera-triggered process restart resumes
    // the photo step instead of losing it.
    private fun showPhotoPrompt(kind: String, resultUid: String? = null) {
        photoPrompt = kind; photoCount = 0
        photoPromptResultUid = resultUid
        prefs.edit()
            .putString("photoPrompt", kind)
            .apply {
                if (resultUid == null) remove("photoPromptResultUid")
                else putString("photoPromptResultUid", resultUid)
            }
            .apply()
    }
    fun dismissPhotoPrompt() {
        val kind = photoPrompt
        if (kind == "setup") updateSetupPhotosNeeded(promptPhotoCount(kind) == 0)
        if (kind == "after") {
            resultPromptUid = photoPromptResultUid ?: results.firstOrNull()?.uid
        }
        photoPrompt = null
        photoPromptResultUid = null
        pendingCameraUri = null
        prefs.edit()
            .remove("photoPrompt")
            .remove("photoPromptResultUid")
            .remove("pendingCameraUri")
            .apply()
    }

    private fun updateSetupPhotosNeeded(needed: Boolean) {
        setupPhotosNeeded = needed
        prefs.edit().putBoolean("setupPhotosNeeded", needed).apply()
    }

    fun requestSetupPhotos() = showPhotoPrompt("setup")

    fun finishResultPrompt(showLog: Boolean = true) {
        resultPromptUid = null
        if (showLog) showFullLog = true
    }

    fun hideFullLog() {
        showFullLog = false
    }

    /**
     * Create and remember the camera target before launching the external
     * camera. Android may destroy this activity while that camera is open, so
     * keeping the URI only in Compose state loses the returning result.
     */
    fun beginPhotoCapture(): Uri? {
        val kind = photoPrompt ?: return null
        val ownerFolder = photoPromptResultUid
            ?.let { uid -> results.firstOrNull { it.uid == uid }?.shotFolder }
            ?.takeIf { it.isNotBlank() }
        val target = if (kind == "after" && ownerFolder != null) {
            session.newPhotoUriInFolder(ownerFolder, kind)
        } else {
            session.newPhotoUri(kind, pendingLabel.trim())
        }
        return target?.also { uri ->
            pendingCameraUri = uri
            prefs.edit().putString("pendingCameraUri", uri.toString()).apply()
        }
    }

    fun promptPhotos(kind: String): List<Uri> {
        val ownerFolder = photoPromptResultUid
            ?.let { uid -> results.firstOrNull { it.uid == uid }?.shotFolder }
            ?.takeIf { it.isNotBlank() }
        return if (kind == "after" && ownerFolder != null) {
            session.listPhotos(ownerFolder)
        } else {
            session.listPromptPhotos(kind, pendingLabel.trim())
        }
    }

    fun setupPhotos(): List<Uri> = session.listPromptPhotos("setup", pendingLabel.trim())

    val canAddSetupPhotos: Boolean get() = !session.currentTestLogged()

    private fun promptPhotoCount(kind: String): Int = promptPhotos(kind).size

    fun importPromptPhotos(uris: List<Uri>) {
        val kind = photoPrompt ?: return
        val ownerFolder = photoPromptResultUid
            ?.let { uid -> results.firstOrNull { it.uid == uid }?.shotFolder }
            ?.takeIf { it.isNotBlank() }
        var added = 0
        for (uri in uris) {
            val imported = if (kind == "after" && ownerFolder != null) {
                session.importPhoto(ownerFolder, uri)
            } else {
                session.importPromptPhoto(kind, pendingLabel.trim(), uri)
            }
            if (imported) added++
        }
        if (added > 0) {
            photoCount += added
            photoRevision++
            if (kind == "after") {
                autoThumbnailForFirstResultPhoto(
                    photoPromptResultUid ?: results.firstOrNull()?.uid
                )
            }
            if (kind == "setup") updateSetupPhotosNeeded(false)
        }
    }

    /** Image URIs already saved in a result's folder (thumbnails). */
    fun photosFor(r: TestResult): List<Uri> = session.listPhotos(r.shotFolder)

    fun completePhotoCapture(ok: Boolean) {
        val uri = pendingCameraUri ?: return
        pendingCameraUri = null
        prefs.edit().remove("pendingCameraUri").apply()
        if (ok) {
            photoCount++
            photoRevision++
            if (photoPrompt == "after") {
                autoThumbnailForFirstResultPhoto(
                    photoPromptResultUid ?: results.firstOrNull()?.uid,
                    uri,
                )
            }
            if (photoPrompt == "setup") updateSetupPhotosNeeded(false)
        }
        else runCatching {
            getApplication<Application>().contentResolver.delete(uri, null, null)
        }
    }

    fun deletePromptPhoto(uri: Uri) {
        val kind = photoPrompt ?: "setup"
        if (session.deletePhoto(uri)) {
            photoRevision++
            if (kind == "setup") updateSetupPhotosNeeded(promptPhotoCount("setup") == 0)
        }
    }

    fun openDataFolder() = session.openFolder(getApplication())

    // The distance is kept exactly as the user typed it, in their chosen unit.
    // Converting to meters and back would turn "12 in" into 11.999… on screen.
    var distanceValue by mutableStateOf(prefs.getFloat("distanceValue", 0f).toDouble())
        private set
    var distanceUnit by mutableStateOf(
        runCatching { DistanceUnit.valueOf(prefs.getString("unit", "INCHES")!!) }
            .getOrDefault(DistanceUnit.INCHES)
    )
        private set

    /** Meters, derived only for the velocity math. */
    val distanceM: Double get() = distanceValue * distanceUnit.toMeters

    /** Non-null while the user is re-testing a sensor from the dashboard. */
    var retestSensor by mutableStateOf<Int?>(null)
        private set

    // Break-screens are consumed by every shot: these flip false on each new
    // result, and both must be re-verified before the next arm.
    var sensor1Ready by mutableStateOf(false)
        private set
    var sensor2Ready by mutableStateOf(false)
        private set

    private fun setSensorReady(sensor: Int, ready: Boolean) {
        if (sensor == 1) sensor1Ready = ready else sensor2Ready = ready
        prefs.edit().putBoolean(readyKey(sensor), ready).apply()
    }

    private fun readyKey(sensor: Int) = "ready_${ble.deviceStorageKey}_$sensor"

    private fun loadDeviceReadiness() {
        sensor1Ready = prefs.getBoolean(readyKey(1), false)
        sensor2Ready = prefs.getBoolean(readyKey(2), false)
    }

    private val setupDone: Boolean
        get() = prefs.getBoolean("setupDone_${ble.deviceStorageKey}", false)

    val isSimulation: Boolean get() = ble.isSimulation

    // --------------------------------------------------------- calibration
    // Keys: "b1"/"b2" = bare-port baseline, "l1"/"l2" = loaded (sensor attached).
    val calData = mutableStateMapOf<String, CalEntry>()
    // Tracks which mode's records are currently loaded (null until first load).
    private var loadedMode: Boolean? = null
    private var loadedNamespace: String? = null
    var calRunning by mutableStateOf(false)
        private set
    private val calQueue = ArrayDeque<Pair<Int, CalPhase>>()
    private var pendingCal: Pair<Int, CalPhase>? = null
    private var calTimeoutJob: Job? = null

    init {
        ble.simDistanceM = distanceM
        reloadForMode()
        viewModelScope.launch { ble.cal.collect { onCalReading(it) } }
        viewModelScope.launch { ble.results.collect { onRawResult(it) } }
        viewModelScope.launch { ble.traces.collect { onRawTrace(it) } }
        viewModelScope.launch {
            ble.health.collect { health -> health?.let { appendHealthHistory(it) } }
        }
        viewModelScope.launch {
            ble.hwInfo.collect { info ->
                if (info?.mcuSerial?.isNotBlank() == true) {
                    loadDeviceReadiness()
                    reloadForMode()
                }
            }
        }
        // Latch sensor readiness whenever a verify test passes (wizard or retest).
        viewModelScope.launch {
            ble.status.collect { st ->
                when (st?.state) {
                    Proto.ST_VERIFY1_OK -> if (!sensor1Ready) setSensorReady(1, true)
                    Proto.ST_VERIFY2_OK -> if (!sensor2Ready) setSensorReady(2, true)
                }
            }
        }
        viewModelScope.launch {
            var prev = ConnState.DISCONNECTED
            ble.connState.collect { cs ->
                when (cs) {
                    ConnState.CONNECTED -> {
                        setUiMode(if (ble.isSimulation) "sim" else "real")
                        loadDeviceReadiness()
                        reloadForMode()   // swap to this mode's isolated records
                        if (screen == Screen.CONNECT) {
                            screen = if (setupDone) Screen.DASHBOARD else Screen.BASELINE
                        }
                    }
                    ConnState.DISCONNECTED ->
                        // Leave the dashboard only when a real device session
                        // ended. A stopped scan (SCANNING -> DISCONNECTED, e.g.
                        // when entering manual mode) must not kick us back.
                        if (prev == ConnState.CONNECTED || prev == ConnState.RECONNECTING ||
                            prev == ConnState.CONNECTING
                        ) {
                            setUiMode("")
                            screen = Screen.CONNECT
                            retestSensor = null
                        }
                    else -> Unit
                }
                prev = cs
            }
        }

        // A camera capture can kill the process mid-session. Resume the photo
        // dialog solely from its persisted state: setupDone is device-keyed by
        // MCU serial, which is unavailable until BLE reconnects and therefore
        // cannot safely gate camera restoration.
        photoPrompt = prefs.getString("photoPrompt", null)
        if (photoPrompt != null) {
            screen = Screen.DASHBOARD
            when (prefs.getString("uiMode", "")) {
                "sim" -> ble.connectSimulated()
                "manual" -> Unit
                else -> ble.reconnectLast()
            }
        }
        // Normal launches still start at mode select. Photo capture is the one
        // exception: the OS can recreate us after camera approval, so resume the
        // dashboard prompt and restore whichever mode opened the camera.
    }

    // -------------------------------------------------------------- results

    /** Shots just received and not yet reviewed — drives the results screen
     *  that appears after (re)connecting, before the after-photos prompt. */
    val newShots = mutableStateListOf<TestResult>()
    private val pendingTraceUids = mutableMapOf<Int, String>()
    private val seenResultKeys = linkedSetOf<String>().apply {
        prefs.getString("seenResultKeys", "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach(::add)
    }

    fun dismissShotReview() {
        val photoOwnerUid = newShots.lastOrNull()?.uid ?: results.firstOrNull()?.uid
        newShots.clear()
        showPhotoPrompt("after", photoOwnerUid)
    }

    private fun onRawResult(r: RawResult) {
        val resultKey = if (r.bootId != 0L) {
            "${r.bootId}:${r.id}"
        } else {
            "legacy:${r.id}:${r.splitNs}:${r.epochSec}"
        }
        // FETCH after a reconnect can re-deliver a result we already stored.
        val existing = results.firstOrNull {
            it.deviceResultId == r.id && if (r.bootId != 0L && it.bootId != 0L) {
                it.bootId == r.bootId
            } else {
                it.splitNs == r.splitNs
            }
        }
        val traceCapable =
            r.fwMajor >= 3 ||
                ((ble.hwInfo.value?.capabilities ?: 0) and Proto.CAP_EDGE_TRACE) != 0
        if (existing == null && resultKey in seenResultKeys) {
            // The result was already committed before an activity/process
            // recreation. Never roll a second test folder for a BLE re-delivery.
            ble.sendCommand(Proto.CMD_ACK, r.id)
            return
        }
        if (existing == null) {
            val rec = TestResult(
                uid = UUID.randomUUID().toString(),
                deviceResultId = r.id,
                splitNs = r.splitNs,
                distanceM = distanceM,
                label = pendingLabel.trim(),
                epochMillis = if (r.epochSec > 0) r.epochSec * 1000L else null,
                shotType = pendingShotType.ifBlank { "Standard" },
                tool = pendingTool.trim(),
                disruptorLoading = pendingDisruptorLoading.trim(),
                projectileType = pendingProjectileType.trim().ifBlank { "Water" },
                target = pendingTarget.trim(),
                targetDistValue = pendingTargetDistVal.replace(',', '.').toDoubleOrNull(),
                targetDistUnit = pendingTargetDistUnit,
                deviceSerial = ble.hwInfo.value?.mcuSerial.orEmpty(),
                resultFlags = r.flags,
                rawStartTicks = r.startTicks,
                rawStopTicks = r.stopTicks,
                batteryMv = r.batteryMv,
                portFlags = r.portFlags,
                bootId = r.bootId,
                resetCause = r.resetCause,
                hardwareRevision = r.hwRev,
                firmwareVersion = if (r.fwMajor > 0) "${r.fwMajor}.${r.fwMinor}" else "",
                formatVersion = r.formatVersion,
                crcValid = r.crcValid,
            )
            rec.shotFolder = session.logShot(rec.label, shotJson(rec))
            rec.thumbnailUri = session.listPhotos(rec.shotFolder).firstOrNull()?.toString() ?: ""
            results.add(0, rec)
            persist()
            rememberResultKey(resultKey)
            prefs.edit()
                .putString("pendShotType", pendingShotType.ifBlank { "Standard" })
                .putString("pendTool", pendingTool)
                .putString("pendLoading", pendingDisruptorLoading)
                .putString("pendProjectile", pendingProjectileType.ifBlank { "Water" })
                .putString("pendTarget", pendingTarget)
                .putString("pendTdVal", pendingTargetDistVal)
                .putString("pendTdUnit", pendingTargetDistUnit)
                .apply()
            pendingLabel = session.suggestedLabel()   // Test2, Test3, …
            newShots.add(rec)   // review dialog first; photos prompt on dismiss
            // A real shot destroys both break-screens: force re-verify (and the
            // sensor-attach flow re-measures the fresh screen's load).
            setSensorReady(1, false)
            setSensorReady(2, false)
            if (traceCapable) {
                pendingTraceUids[r.id] = rec.uid
                ble.sendCommand(Proto.CMD_FETCH_TRACE, r.id)
            } else {
                ble.sendCommand(Proto.CMD_ACK, r.id)
            }
        } else if (traceCapable && !existing.hasWaveform) {
            rememberResultKey(resultKey)
            pendingTraceUids[r.id] = existing.uid
            ble.sendCommand(Proto.CMD_FETCH_TRACE, r.id)
        } else {
            rememberResultKey(resultKey)
            ble.sendCommand(Proto.CMD_ACK, r.id)
        }
    }

    private fun rememberResultKey(key: String) {
        seenResultKeys.remove(key)
        seenResultKeys.add(key)
        while (seenResultKeys.size > 128) {
            seenResultKeys.remove(seenResultKeys.first())
        }
        prefs.edit().putString("seenResultKeys", seenResultKeys.joinToString("\n")).apply()
    }

    fun requestWaveform(uid: String) {
        val result = results.firstOrNull { it.uid == uid } ?: return
        if (result.isManual || result.deviceResultId < 0 || result.hasWaveform) return
        pendingTraceUids[result.deviceResultId] = uid
        ble.sendCommand(Proto.CMD_FETCH_TRACE, result.deviceResultId)
    }

    private fun onRawTrace(trace: RawTrace) {
        val uid = pendingTraceUids.remove(trace.resultId)
            ?: results.firstOrNull { it.deviceResultId == trace.resultId && !it.hasWaveform }?.uid
            ?: return
        val index = results.indexOfFirst { it.uid == uid }
        if (index < 0) return
        val updated = results[index].copy(
            traceFormatVersion = trace.formatVersion,
            traceBaseTicks = trace.baseTicks,
            traceFlags = trace.flags,
            traceData = WaveformCodec.encode(trace.events),
        )
        results[index] = updated
        val newIndex = newShots.indexOfFirst { it.uid == uid }
        if (newIndex >= 0) newShots[newIndex] = updated
        persist()
        ble.sendCommand(Proto.CMD_ACK, trace.resultId)
    }

    fun applyReviewedTiming(uid: String, startOffsetTicks: Long, stopOffsetTicks: Long) {
        val index = results.indexOfFirst { it.uid == uid }
        if (index < 0) return
        val result = results[index]
        val reviewedNs = ticksToNanoseconds(stopOffsetTicks - startOffsetTicks)
        val updated = result.copy(
            reviewedSplitNs = reviewedNs,
            reviewedStartOffsetTicks = startOffsetTicks,
            reviewedStopOffsetTicks = stopOffsetTicks,
            reviewedAtMillis = System.currentTimeMillis(),
        )
        results[index] = updated
        val newIndex = newShots.indexOfFirst { it.uid == uid }
        if (newIndex >= 0) newShots[newIndex] = updated
        persist()
    }

    fun resetReviewedTiming(uid: String) {
        val index = results.indexOfFirst { it.uid == uid }
        if (index < 0) return
        val updated = results[index].copy(
            reviewedSplitNs = null,
            reviewedStartOffsetTicks = null,
            reviewedStopOffsetTicks = null,
            reviewedAtMillis = null,
        )
        results[index] = updated
        val newIndex = newShots.indexOfFirst { it.uid == uid }
        if (newIndex >= 0) newShots[newIndex] = updated
        persist()
    }

    fun updateResult(
        uid: String,
        label: String,
        shotType: String,
        tool: String,
        disruptorLoading: String,
        projectileType: String,
        target: String,
        targetDistValue: Double?,
        targetDistUnit: String,
        passFail: String,
        specialNotes: String,
        epochMillis: Long?,
    ) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        val r = results[idx]
        results[idx] = r.copy(
            label = label,
            shotType = shotType.ifBlank { "Standard" },
            tool = tool,
            disruptorLoading = disruptorLoading,
            projectileType = projectileType.ifBlank { "Water" },
            target = target,
            targetDistValue = targetDistValue,
            targetDistUnit = targetDistUnit,
            passFail = passFail,
            specialNotes = specialNotes,
            outcome = specialNotes,
            epochMillis = epochMillis ?: r.epochMillis,
        )
        persist()
    }

    /** Log a shot with no chronograph connected — velocity typed in (or blank). */
    fun addManualEntry(
        label: String,
        shotType: String,
        tool: String,
        disruptorLoading: String,
        projectileType: String,
        target: String,
        targetDistValue: Double?,
        targetDistUnit: String,
        passFail: String,
        specialNotes: String,
        velocity: Double?,
        velocityIsFps: Boolean,
        epochMillis: Long?,
        photos: List<Uri> = emptyList(),
    ) {
        val mps = velocity?.let { if (velocityIsFps) it / 3.28084 else it }
        val rec = TestResult(
            uid = UUID.randomUUID().toString(),
            deviceResultId = -1,
            splitNs = 0,
            distanceM = 0.0,
            label = label.trim(),
            epochMillis = epochMillis,
            shotType = shotType.trim().ifBlank { "Standard" },
            tool = tool.trim(),
            disruptorLoading = disruptorLoading.trim(),
            projectileType = projectileType.trim().ifBlank { "Water" },
            target = target.trim(),
            targetDistValue = targetDistValue,
            targetDistUnit = targetDistUnit,
            passFail = passFail.trim(),
            specialNotes = specialNotes.trim(),
            outcome = specialNotes.trim(),
            manualVelocityMps = mps,
        )
        rec.shotFolder = session.logShot(rec.label, shotJson(rec))
        for (uri in photos) session.importPhoto(rec.shotFolder, uri)
        rec.thumbnailUri = session.listPhotos(rec.shotFolder).firstOrNull()?.toString() ?: ""
        results.add(0, rec)
        persist()
        pendingLabel = session.suggestedLabel()
        showPhotoPrompt("after", rec.uid)
    }

    fun setResultThumbnail(uid: String, uri: String) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        results[idx] = results[idx].copy(thumbnailUri = uri)
        persist()
    }

    /** Copy user-picked images into a result's shot folder (edit dialog). */
    fun attachPhotosToResult(uid: String, uris: List<Uri>) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0 || uris.isEmpty()) return
        val r = results[idx]
        val rel = session.folderForResult(r.shotFolder, r.uid)
        if (r.shotFolder.isBlank()) {
            results[idx] = r.copy(shotFolder = rel)
            persist()
        }
        for (uri in uris) session.importPhoto(rel, uri)
        autoThumbnailForFirstResultPhoto(uid)
        photoRevision++
    }

    private fun autoThumbnailForFirstResultPhoto(uid: String?, preferred: Uri? = null) {
        if (uid == null) return
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0 || results[idx].thumbnailUri.isNotBlank()) return
        val uri = preferred ?: session.listPhotos(results[idx].shotFolder).firstOrNull() ?: return
        results[idx] = results[idx].copy(thumbnailUri = uri.toString())
        persist()
    }

    fun deleteResultPhoto(uid: String, uri: Uri) {
        if (session.deletePhoto(uri)) {
            val idx = results.indexOfFirst { it.uid == uid }
            if (idx >= 0 && results[idx].thumbnailUri == uri.toString()) {
                results[idx] = results[idx].copy(thumbnailUri = "")
                persist()
                autoThumbnailForFirstResultPhoto(uid)
            }
            photoRevision++
        }
    }

    /** The per-shot log file written into the shot's folder. */
    private fun shotJson(r: TestResult): JSONObject = JSONObject()
        .put("uid", r.uid)
        .put("source", if (r.isManual) "manual" else "device")
        .put("label", r.label)
        .put("shotType", r.shotType.ifBlank { "Standard" })
        .put("tool", r.tool)
        .put("disruptorTypeModel", r.tool)
        .put("disruptorLoading", r.disruptorLoading)
        .put("projectileType", r.projectileType.ifBlank { "Water" })
        .put("target", r.target)
        .put("targetDistUnit", r.targetDistUnit)
        .put("passFail", r.passFail)
        .put("specialNotes", r.specialNotes.ifBlank { r.outcome })
        .put("outcome", r.specialNotes.ifBlank { r.outcome })
        .put("splitNs", r.splitNs)
        .put("automaticSplitNs", r.splitNs)
        .put("reviewedSplitNs", r.reviewedSplitNs ?: JSONObject.NULL)
        .put("effectiveSplitNs", r.effectiveSplitNs)
        .put("distanceM", r.distanceM)
        .put("velocityMps", r.metersPerSecond)
        .put("velocityFps", r.feetPerSecond)
        .put("accuracyEnvelopePercent", accuracyEnvelopePercentFor(r))
        .put("ciPercent", accuracyEnvelopePercentFor(r))
        .put("deviceSerial", r.deviceSerial)
        .put("resultFlags", r.resultFlags)
        .put("rawStartTicks", r.rawStartTicks)
        .put("rawStopTicks", r.rawStopTicks)
        .put("batteryMv", r.batteryMv ?: JSONObject.NULL)
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
        .put("reviewedStartOffsetTicks", r.reviewedStartOffsetTicks ?: JSONObject.NULL)
        .put("reviewedStopOffsetTicks", r.reviewedStopOffsetTicks ?: JSONObject.NULL)
        .put("reviewedAtMillis", r.reviewedAtMillis ?: JSONObject.NULL)
        .put("epochMillis", r.epochMillis ?: -1L)
        .apply { r.targetDistValue?.let { put("targetDistValue", it) } }

    fun deleteResult(uid: String) {
        results.removeAll { it.uid == uid }
        persist()
    }

    private fun persist() = store.save(results.toList())

    /** Per-mode namespacing so simulated calibration never touches real cal data. */
    private fun calKey(key: String) = "cal_${ble.deviceStorageKey}_$key"
    private fun calHistoryFile() = File(
        getApplication<Application>().filesDir,
        if (ble.isSimulation) "cal_history_sim.jsonl" else "cal_history.jsonl"
    )

    private fun healthHistoryFile() = File(
        getApplication<Application>().filesDir,
        if (ble.isSimulation) "health_history_sim.jsonl" else "health_history.jsonl"
    )

    private var lastHealthFingerprint = ""

    private fun appendHealthHistory(health: HealthStatus) {
        if (health.checkedAtBootMs == 0L) return
        val fingerprint = "${ble.deviceStorageKey}:${health.checkedAtBootMs}:" +
            "${health.channel1.flags}:${health.channel2.flags}:" +
            "${health.channel1.signatureNs}:${health.channel2.signatureNs}"
        if (fingerprint == lastHealthFingerprint) return
        lastHealthFingerprint = fingerprint
        runCatching {
            val line = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("deviceSerial", ble.hwInfo.value?.mcuSerial.orEmpty())
                .put("deviceKey", ble.deviceStorageKey)
                .put("checkedAtBootMs", health.checkedAtBootMs)
                .put("ready", health.ready)
                .put("channel1Flags", health.channel1.flags)
                .put("channel2Flags", health.channel2.flags)
                .put("channel1SignatureNs", health.channel1.signatureNs)
                .put("channel2SignatureNs", health.channel2.signatureNs)
            healthHistoryFile().appendText(line.toString() + "\n")
        }
    }

    // Swap all in-memory records to the active mode's isolated store. Called at
    // startup and whenever a connection establishes the real/sim mode, so real
    // and simulated results, calibration, and folders never intermix.
    private fun reloadForMode() {
        val sim = ble.isSimulation
        val namespace = ble.deviceStorageKey
        if (loadedMode == sim && loadedNamespace == namespace) return
        loadedMode = sim
        loadedNamespace = namespace
        results.clear()
        results.addAll(store.load())
        calData.clear()
        for (key in listOf("b1", "b2", "l1", "l2")) {
            prefs.getString(calKey(key), null)?.split(",")?.let { p ->
                if (p.size >= 5) calData[key] = CalEntry(
                    p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toLong()
                )
            }
        }
        pendingLabel = session.suggestedLabel()
        projectPrompt = session.needsProjectPrompt()
    }

    // --------------------------------------------------- calibration engine

    private fun onCalReading(r: CalReading) {
        calTimeoutJob?.cancel()
        val slot = pendingCal
        if (slot != null && slot.first == r.channel) recordCal(slot, r)
        pendingCal = null
        nextCal()
    }

    private fun recordCal(slot: Pair<Int, CalPhase>, r: CalReading) {
        val key = (if (slot.second == CalPhase.BARE) "b" else "l") + slot.first
        val e = CalEntry(r.medianNs, r.stddevNs, r.samples, r.status, System.currentTimeMillis())
        calData[key] = e
        prefs.edit()
            .putString(calKey(key), "${e.medianNs},${e.stddevNs},${e.samples},${e.status},${e.at}")
            .apply()
        appendCalHistory(key, r)
    }

    private fun enqueueCal(items: List<Pair<Int, CalPhase>>) {
        calQueue.addAll(items)
        if (!calRunning) nextCal()
    }

    private fun nextCal() {
        val next = calQueue.removeFirstOrNull()
        if (next == null) {
            calRunning = false
            pendingCal = null
            return
        }
        calRunning = true
        pendingCal = next
        ble.sendCommand(Proto.CMD_CALIBRATE, next.first)
        calTimeoutJob?.cancel()
        calTimeoutJob = viewModelScope.launch {
            delay(5_000)   // device never answered (e.g. old firmware) - move on
            val timedOut = pendingCal
            if (timedOut != null) {
                recordCal(
                    timedOut,
                    CalReading(
                        channel = timedOut.first,
                        status = 2,
                        samples = 0,
                        medianNs = 0,
                        meanNs = 0,
                        stddevNs = 0,
                        minNs = 0,
                    ),
                )
            }
            pendingCal = null
            nextCal()
        }
    }

    fun startBaselineCal() {
        ble.setSimCalPhase(true)
        enqueueCal(listOf(1 to CalPhase.BARE, 2 to CalPhase.BARE))
    }

    fun startLoadedCal(channel: Int) {
        ble.setSimCalPhase(false)
        enqueueCal(listOf(channel to CalPhase.LOADED))
    }

    fun recalibrateChannels() {
        ble.setSimCalPhase(false)
        enqueueCal(listOf(1 to CalPhase.LOADED, 2 to CalPhase.LOADED))
    }

    fun cancelCalibrationToDashboard() {
        calTimeoutJob?.cancel()
        calQueue.clear()
        pendingCal = null
        calRunning = false
        retestSensor = null
        inWizard = false
        ble.sendCommand(Proto.CMD_CANCEL)
        screen = Screen.DASHBOARD
    }

    /** Raw engineering log, one JSON line per sweep, for later analysis. */
    private fun appendCalHistory(key: String, r: CalReading) {
        runCatching {
            val line = JSONObject()
                .put("key", key)
                .put("at", System.currentTimeMillis())
                .put("medianNs", r.medianNs)
                .put("meanNs", r.meanNs)
                .put("stddevNs", r.stddevNs)
                .put("minNs", r.minNs)
                .put("samples", r.samples)
                .put("status", r.status)
            calHistoryFile().appendText(line.toString() + "\n")
        }
    }

    /** Loaded-minus-bare for one channel: the cable+sensor contribution in ns. */
    fun channelLoadNs(channel: Int): Long? {
        val b = calData["b$channel"] ?: return null
        val l = calData["l$channel"] ?: return null
        if (!b.isUsable || !l.isUsable) return null
        return l.medianNs - b.medianNs
    }

    /** Channel-to-channel difference of the load terms — the number that matters. */
    fun channelMismatchNs(): Long? {
        val a = channelLoadNs(1) ?: return null
        val b = channelLoadNs(2) ?: return null
        return abs(a - b)
    }

    fun rcDelayText(ns: Long): String {
        val absNs = abs(ns)
        val (value, unit) = when {
            absNs >= 1_000_000L -> ns / 1_000_000.0 to "ms"
            absNs >= 1_000L -> ns / 1_000.0 to "us"
            else -> ns.toDouble() to "ns"
        }
        val text = when {
            value >= 100.0 -> "%.0f".format(value)
            value >= 10.0 -> "%.1f".format(value)
            else -> "%.2f".format(value)
        }.trimFractionZeros()
        return "$text $unit"
    }

    private fun String.trimFractionZeros(): String =
        if (contains('.')) trimEnd('0').trimEnd('.') else this

    fun baselineTooHigh(entry: CalEntry?): Boolean =
        entry?.isUsable == true && entry.medianNs >= 3_000

    fun baselineSignatureText(entry: CalEntry?): String =
        entry?.let { rcDelayText(it.medianNs) } ?: ""

    fun channelMismatchPercent(): Double? {
        val a = channelLoadNs(1) ?: return null
        val b = channelLoadNs(2) ?: return null
        val avg = (a + b) / 2.0
        if (avg <= 0.0) return null
        return abs(a - b) / avg * 100.0
    }

    /**
     * Conservative accuracy envelope for one result, as a percent of velocity.
     * Combines hardware timer uncertainty, clock drift, edge jitter, measured
     * channel balance, loaded-calibration repeatability, and the user's gate
     * spacing uncertainty. Newer hardware and better calibration automatically
     * tighten this without changing the app.
     */
    fun accuracyEnvelopePercentFor(r: TestResult): Double {
        if (r.isManual || r.effectiveSplitNs == 0L || r.distanceM <= 0) return 0.0
        return accuracyEnvelopePercentRaw(kotlin.math.abs(r.effectiveSplitNs.toDouble()), r.distanceM)
    }

    fun ciPercentFor(r: TestResult): Double = accuracyEnvelopePercentFor(r)

    /** Expected envelope at the current gate spacing for a reference velocity. */
    fun estimatedAccuracyEnvelopeAtCurrentSpacing(fps: Double = 3000.0): Double? {
        if (distanceM <= 0) return null
        val splitNs = distanceM / (fps / 3.28084) * 1e9
        return accuracyEnvelopePercentRaw(splitNs, distanceM)
    }

    fun estimatedCiAtCurrentSpacing(fps: Double = 3000.0): Double? =
        estimatedAccuracyEnvelopeAtCurrentSpacing(fps)

    private fun accuracyEnvelopePercentRaw(splitNs: Double, gateM: Double): Double {
        val hw = ble.hwInfo.value ?: HwInfo.DEFAULT
        val tickNs = hw.tickPs / 1000.0 / sqrt(12.0)
        val jitterNs = hw.edgeJitterNs * sqrt(2.0)             // two independent edges
        val clockNs = splitNs * hw.clockPpm / 1_000_000.0
        val frontEndNs = calibrationFrontEndNs()
        val sigmaT = sqrt(
            tickNs.pow(2.0) + jitterNs.pow(2.0) + clockNs.pow(2.0) + frontEndNs.pow(2.0)
        )
        val sigmaD = 0.0005                                    // 0.5 mm spacing uncertainty
        val rel = sqrt((sigmaT / splitNs).pow(2.0) + (sigmaD / gateM).pow(2.0))
        return 2.58 * rel * 100.0
    }

    private fun calibrationFrontEndNs(): Double {
        val mismatch = channelMismatchNs()
        val mismatchNs = when {
            mismatch == null -> 300.0
            mismatch <= 250L -> 75.0
            mismatch <= 600L -> 125.0
            mismatch <= 1500L -> 220.0
            else -> (mismatch * 0.30).coerceIn(300.0, 800.0)
        }
        val repeatabilityNs = listOfNotNull(calData["l1"], calData["l2"])
            .filter { it.isUsable }
            .map { it.stddevNs.toDouble() }
            .maxOrNull()
            ?.coerceIn(25.0, 250.0)
            ?: 120.0
        return sqrt(mismatchNs.pow(2.0) + repeatabilityNs.pow(2.0))
    }

    // ---------------------------------------------------------- setup flow

    // The wizard sensor steps no longer arm the input on entry — the user
    // first confirms the sensor is plugged in (attach step), and only then
    // does beginTapTest() start listening for the test tap.
    fun continueToSensor1() {
        screen = Screen.SENSOR1
    }

    fun continueToSensor2() {
        screen = Screen.SENSOR2
    }

    /** Sim stand-in for physically tapping the sensor under test. */
    fun simulateTap() = ble.simulateSensorTap()

    private var inWizard = false

    fun continueToDistance() {
        ble.sendCommand(Proto.CMD_CANCEL)
        inWizard = true
        screen = Screen.DISTANCE
    }

    fun saveDistance(value: Double, unit: DistanceUnit) {
        distanceValue = value
        distanceUnit = unit
        ble.simDistanceM = distanceM
        prefs.edit()
            .putFloat("distanceValue", value.toFloat())
            .putString("unit", unit.name)
            .putBoolean("setupDone_${ble.deviceStorageKey}", true)
            .apply()
        screen = Screen.DASHBOARD
        if (inWizard) {
            inWizard = false
            updateSetupPhotosNeeded(true)
            showPhotoPrompt("setup")   // capture the rig as it stands for this test
        }
    }

    /** Distance expressed in the user's chosen unit, for display/editing. */
    fun distanceInUnit(): Double = distanceValue

    // ------------------------------------------------------------ dashboard

    /**
     * Opens the sensor-attach dialog. Deliberately does NOT start listening on
     * the input yet: the wire is first fitted (attach), then verified by a
     * RC signature measurement, and only then armed for the tap test — so
     * movement during placement can't trigger anything.
     */
    fun startRetest(sensor: Int) {
        retestSensor = sensor
    }

    /** Step 3 of the attach flow: now it's safe to listen for the test tap. */
    fun beginTapTest(sensor: Int) =
        ble.sendCommand(if (sensor == 1) Proto.CMD_VERIFY1 else Proto.CMD_VERIFY2)

    fun finishRetest(verified: Boolean) {
        retestSensor = null
        ble.sendCommand(Proto.CMD_CANCEL)
    }

    fun arm() = ble.sendCommand(Proto.CMD_ARM)
    fun armWithOverride() = ble.sendCommand(Proto.CMD_ARM_OVERRIDE)
    fun disarm() = ble.sendCommand(Proto.CMD_DISARM)
    fun syncTime() = ble.syncTime()
    fun checkPorts() = ble.sendCommand(Proto.CMD_HEALTH)
    fun identifyLogger() = ble.sendCommand(Proto.CMD_IDENTIFY)
    fun setSimFault(fault: SimFault) = ble.setSimFault(fault)

    fun changeDistance() {
        inWizard = false
        screen = Screen.DISTANCE
    }

    /** Manual logging without any chronograph — straight to the dashboard. */
    fun enterManualMode() {
        setUiMode("manual")
        screen = Screen.DASHBOARD
    }

    /** TopBar action: disconnect if connected, otherwise leave the dashboard. */
    fun disconnectOrExit() {
        setUiMode("")
        if (ble.connState.value == ConnState.DISCONNECTED) {
            screen = Screen.CONNECT
        } else {
            ble.disconnect()
        }
    }

    /** Re-run the whole wizard, including a fresh bare-port baseline. */
    fun redoSetup() {
        screen = Screen.BASELINE
    }

    fun disconnect() {
        setUiMode("")
        ble.disconnect()
    }

    fun connectSimulated() {
        setUiMode("sim")
        ble.connectSimulated()
    }
    fun simulateSignalLoss() = ble.simulateSignalLoss()

    // The active mode is persisted so that if Android kills the process while
    // the external camera is open (common with full-res capture), reopening
    // the app restores where you were instead of dropping to the scan screen.
    private fun setUiMode(mode: String) = prefs.edit().putString("uiMode", mode).apply()

    override fun onCleared() {
        ble.disconnect()
    }
}
