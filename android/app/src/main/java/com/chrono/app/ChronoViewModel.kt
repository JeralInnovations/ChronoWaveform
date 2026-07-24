package com.chrono.app

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import android.net.Uri
import com.chrono.app.data.DistanceUnit
import com.chrono.app.data.ResultStore
import com.chrono.app.data.SessionManager
import com.chrono.app.data.TestResult
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.chrono.app.ble.HwInfo
import com.chrono.app.ble.SimFault
import com.chrono.app.ble.HealthStatus
import com.chrono.app.ble.DeviceStatus
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
    private var projectRefreshJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleProjectRefresh(500)
        }
    }

    var screen by mutableStateOf(Screen.CONNECT)
        private set

    private var startupRoutingPending = true
    private var startupHasShotData = false
    private var resetReadinessThisLaunch = true

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
        refreshProjectData()
    }
    fun keepProject() {
        session.continueProject()
        pendingLabel = session.suggestedLabel()
        projectPrompt = false
        refreshProjectData()
    }

    fun commitPendingLabel() {
        pendingLabel = session.commitCurrentTestLabel(pendingLabel)
    }

    private fun preparePendingTestLabel(): String {
        val label = session.prepareTestLabel(pendingLabel)
        pendingLabel = label
        return label
    }

    /** "setup" or "after" while the photo dialog is showing. */
    var photoPrompt by mutableStateOf<String?>(null)
        private set
    var photoCount by mutableStateOf(0)
        private set
    var photoRevision by mutableStateOf(0)
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
    private fun showPhotoPrompt(kind: String) {
        photoPrompt = kind; photoCount = 0
        prefs.edit().putString("photoPrompt", kind).apply()
    }
    fun dismissPhotoPrompt() {
        val kind = photoPrompt
        if (kind == "setup") updateSetupPhotosNeeded(promptPhotoCount(kind) == 0)
        if (kind == "after") resultPromptUid = results.firstOrNull()?.uid
        photoPrompt = null
        prefs.edit().remove("photoPrompt").apply()
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

    /** Create the next photo target inside the right test folder. */
    fun newPhotoUri(): Uri? {
        val kind = photoPrompt ?: return null
        val label = if (kind == "setup") preparePendingTestLabel() else pendingLabel.trim()
        return session.newPhotoUri(kind, label)
    }

    fun promptPhotos(kind: String): List<Uri> =
        session.listPromptPhotos(kind, pendingLabel.trim())

    fun setupPhotos(): List<Uri> = session.listPromptPhotos("setup", pendingLabel.trim())

    val canAddSetupPhotos: Boolean get() = !session.currentTestLogged()

    private fun promptPhotoCount(kind: String): Int = promptPhotos(kind).size

    fun importPromptPhotos(uris: List<Uri>) {
        val kind = photoPrompt ?: return
        val label = if (kind == "setup") preparePendingTestLabel() else pendingLabel.trim()
        var added = 0
        for (uri in uris) {
            if (session.importPromptPhoto(kind, label, uri)) added++
        }
        if (added > 0) {
            photoCount += added
            photoRevision++
            if (kind == "after") autoThumbnailForFirstResultPhoto(results.firstOrNull()?.uid)
            if (kind == "setup") updateSetupPhotosNeeded(false)
        }
    }

    /** Image URIs already saved in a result's folder (thumbnails). */
    fun photosFor(r: TestResult): List<Uri> = session.listPhotos(r.shotFolder)

    fun photoSaved(ok: Boolean, uri: Uri) {
        if (ok) {
            photoCount++
            photoRevision++
            if (photoPrompt == "after") autoThumbnailForFirstResultPhoto(results.firstOrNull()?.uid, uri)
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
    var measurementErrorUnit by mutableStateOf(
        runCatching {
            DistanceUnit.valueOf(
                prefs.getString("measurementErrorUnit", distanceUnit.name)!!,
            )
        }.getOrDefault(distanceUnit)
    )
        private set
    var measurementErrorValue by mutableStateOf(
        prefs.getFloat(
            "measurementErrorValue",
            prefs.getFloat(
                "distanceUncertaintyValue",
                (0.0005 / measurementErrorUnit.toMeters).toFloat(),
            ),
        ).toDouble()
    )
        private set

    /** Meters, derived only for the velocity math. */
    val distanceM: Double get() = distanceValue * distanceUnit.toMeters
    val measurementErrorM: Double
        get() = measurementErrorValue * measurementErrorUnit.toMeters

    /** Non-null while the user is re-testing a sensor from the dashboard. */
    var retestSensor by mutableStateOf<Int?>(null)
        private set

    // Break-screens are consumed by every shot: these flip false on each new
    // result, and both must be re-verified before the next arm.
    var sensor1Ready by mutableStateOf(false)
        private set
    var sensor2Ready by mutableStateOf(false)
        private set
    private var setupResultRecorded = false

    private fun setSensorReady(sensor: Int, ready: Boolean) {
        val bothWereReady = sensor1Ready && sensor2Ready
        if (sensor == 1) sensor1Ready = ready else sensor2Ready = ready
        prefs.edit().putBoolean(readyKey(sensor), ready).apply()
        if (sensor1Ready && sensor2Ready) {
            resetReadinessThisLaunch = false
            if (!bothWereReady) {
                setupResultRecorded = false
                prefs.edit().putBoolean(setupResultKey(), false).apply()
            }
        }
    }

    private fun readyKey(sensor: Int) = "ready_${ble.deviceStorageKey}_$sensor"
    private fun setupResultKey() = "setup_result_recorded_${ble.deviceStorageKey}"

    private fun loadDeviceReadiness() {
        setupResultRecorded = prefs.getBoolean(setupResultKey(), false)
        if (resetReadinessThisLaunch) {
            sensor1Ready = false
            sensor2Ready = false
            return
        }
        sensor1Ready = prefs.getBoolean(readyKey(1), false)
        sensor2Ready = prefs.getBoolean(readyKey(2), false)
    }

    private fun routeFirstConnection(status: DeviceStatus?) {
        if (!startupRoutingPending || ble.connState.value != ConnState.CONNECTED || status == null) return

        val resumeExistingRun = status.state == Proto.ST_ARMED ||
            status.state == Proto.ST_RUNNING || status.pendingCount > 0 || startupHasShotData
        startupRoutingPending = false

        if (resumeExistingRun) {
            resetReadinessThisLaunch = false
            loadDeviceReadiness()
            screen = Screen.DASHBOARD
        } else {
            resetReadinessThisLaunch = true
            sensor1Ready = false
            sensor2Ready = false
            session.beginNewTest()
            pendingLabel = session.suggestedLabel()
            ble.sendCommand(Proto.CMD_CANCEL)
            screen = Screen.BASELINE
        }
    }

    val isSimulation: Boolean get() = ble.isSimulation

    // --------------------------------------------------------- calibration
    // Keys: "b1"/"b2" = bare-port baseline, "l1"/"l2" = loaded (sensor attached).
    val calData = mutableStateMapOf<String, CalEntry>()
    // Tracks which mode's records are currently loaded (null until first load).
    private var loadedMode: Boolean? = null
    private var loadedNamespace: String? = null
    private var portCheckRequested = false
    var calRunning by mutableStateOf(false)
        private set
    private val calQueue = ArrayDeque<Pair<Int, CalPhase>>()
    private var pendingCal: Pair<Int, CalPhase>? = null
    private var calTimeoutJob: Job? = null

    init {
        ble.simDistanceM = distanceM
        reloadForMode()
        runCatching {
            app.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                mediaObserver,
            )
        }
        viewModelScope.launch { ble.cal.collect { onCalReading(it) } }
        viewModelScope.launch { ble.results.collect { onRawResult(it) } }
        viewModelScope.launch {
            ble.health.collect { health ->
                health?.let {
                    appendHealthHistory(it)
                    if (portCheckRequested && it.checkedAtBootMs > 0) {
                        portCheckRequested = false
                        setSensorReady(1, !it.channel1.serious)
                        setSensorReady(2, !it.channel2.serious)
                    }
                }
            }
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
                routeFirstConnection(st)
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
                        routeFirstConnection(ble.status.value)
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

        // A camera capture can kill the process mid-session. A persisted prompt
        // is itself proof that setup reached the dashboard; do not consult the
        // device-scoped setupDone key here because the MCU serial is not known
        // until BLE reconnects and deviceStorageKey temporarily uses the address.
        photoPrompt = prefs.getString("photoPrompt", null)
        if (photoPrompt != null) {
            startupRoutingPending = false
            resetReadinessThisLaunch = false
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

    fun dismissShotReview() {
        newShots.clear()
        showPhotoPrompt("after")
    }

    private fun onRawResult(r: RawResult) {
        startupHasShotData = true
        routeFirstConnection(ble.status.value)

        // FETCH after a reconnect can re-deliver a result we already stored.
        val duplicate = results.any { existing ->
            existing.deviceResultId == r.id && when {
                r.bootId != 0L && existing.bootId != 0L -> existing.bootId == r.bootId
                else -> existing.splitNs == r.splitNs
            }
        }
        if (!duplicate && !setupResultRecorded) {
            val testLabel = preparePendingTestLabel()
            val rec = TestResult(
                uid = UUID.randomUUID().toString(),
                deviceResultId = r.id,
                splitNs = r.splitNs,
                distanceM = distanceM,
                measurementErrorM = measurementErrorM,
                measurementErrorUnit = measurementErrorUnit.name,
                label = testLabel,
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
            setupResultRecorded = true
            prefs.edit().putBoolean(setupResultKey(), true).apply()
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
            newShots.clear()
            newShots.add(rec)   // exactly one result belongs to this setup
            // A real shot destroys both break-screens: force re-verify (and the
            // sensor-attach flow re-measures the fresh screen's load).
            setSensorReady(1, false)
            setSensorReady(2, false)
        }
        // Tell the device it can forget this result now that it's stored on the phone.
        ble.sendCommand(Proto.CMD_ACK, r.id)
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
        val existingRel = session.folderForResult(r.shotFolder, r.uid)
        val (rel, effectiveLabel) = session.renameTestFolder(existingRel, label)
        val updated = r.copy(
            label = effectiveLabel,
            shotFolder = rel,
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
        results[idx] = updated
        persist()
        session.updateShot(rel, shotJson(results[idx]))
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
        val previousLabel = session.suggestedLabel()
        session.beginNewTest()
        val requestedLabel =
            if (label.trim() == previousLabel) session.suggestedLabel() else label
        val testLabel = session.prepareTestLabel(requestedLabel)
        val rec = TestResult(
            uid = UUID.randomUUID().toString(),
            deviceResultId = -1,
            splitNs = 0,
            distanceM = 0.0,
            label = testLabel,
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
        showPhotoPrompt("after")
    }

    fun setResultThumbnail(uid: String, uri: String) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        results[idx] = results[idx].copy(thumbnailUri = uri)
        persist()
        session.updateShot(results[idx].shotFolder, shotJson(results[idx]))
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
            session.updateShot(rel, shotJson(results[idx]))
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
        session.updateShot(results[idx].shotFolder, shotJson(results[idx]))
    }

    fun deleteResultPhoto(uid: String, uri: Uri) {
        if (session.deletePhoto(uri)) {
            val idx = results.indexOfFirst { it.uid == uid }
            if (idx >= 0 && results[idx].thumbnailUri == uri.toString()) {
                results[idx] = results[idx].copy(thumbnailUri = "")
                persist()
                autoThumbnailForFirstResultPhoto(uid)
                session.updateShot(results[idx].shotFolder, shotJson(results[idx]))
            }
            photoRevision++
        }
    }

    /** The per-shot log file written into the shot's folder. */
    private fun shotJson(r: TestResult): JSONObject = JSONObject()
        .put("uid", r.uid)
        .put("source", if (r.isManual) "manual" else "device")
        .put("deviceResultId", r.deviceResultId)
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
        .put("signedSplitNs", r.signedSplitNs)
        .put("distanceM", r.distanceM)
        .put("manualVelocityMps", r.manualVelocityMps ?: JSONObject.NULL)
        .put("measurementErrorM", r.measurementErrorM)
        .put("measurementErrorUnit", r.measurementErrorUnit)
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
        .put("epochMillis", r.epochMillis ?: -1L)
        .put("shotFolder", r.shotFolder)
        .put("thumbnailUri", r.thumbnailUri)
        .apply { r.targetDistValue?.let { put("targetDistValue", it) } }

    fun deleteResult(uid: String) {
        val result = results.firstOrNull { it.uid == uid } ?: return
        if (result.shotFolder.isBlank() || session.deleteTestFolder(result.shotFolder)) {
            results.removeAll { it.uid == uid }
            persist()
        }
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
        val loadedResults = session.loadProjectResults()
        results.clear()
        results.addAll(loadedResults)
        store.save(loadedResults)
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

    /** Re-read public shot folders after Files, USB, or another app changes them. */
    fun refreshProjectData() {
        scheduleProjectRefresh(0)
    }

    private fun scheduleProjectRefresh(delayMs: Long) {
        projectRefreshJob?.cancel()
        projectRefreshJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val activeSession = session
            val activeStore = store
            val loadedResults = withContext(Dispatchers.IO) {
                activeSession.loadProjectResults()
            }
            if (session !== activeSession) return@launch
            results.clear()
            results.addAll(loadedResults)
            withContext(Dispatchers.IO) { activeStore.save(loadedResults) }
            val generatedLabel = Regex("^Test[0-9]+$", RegexOption.IGNORE_CASE)
                .matches(pendingLabel.trim())
            if (pendingLabel.isBlank() || generatedLabel) {
                pendingLabel = activeSession.suggestedLabel()
            }
            photoRevision++
        }
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
        if (r.isManual || r.splitNs <= 0 || r.distanceM <= 0) return 0.0
        return accuracyEnvelopePercentRaw(
            r.splitNs.toDouble(),
            r.distanceM,
            r.measurementErrorM,
        )
    }

    fun ciPercentFor(r: TestResult): Double = accuracyEnvelopePercentFor(r)

    /** Expected envelope at the current gate spacing for a reference velocity. */
    fun estimatedAccuracyEnvelopeAtCurrentSpacing(fps: Double = 3000.0): Double? {
        if (distanceM <= 0) return null
        val splitNs = distanceM / (fps / 3.28084) * 1e9
        return accuracyEnvelopePercentRaw(splitNs, distanceM, measurementErrorM)
    }

    fun estimatedCiAtCurrentSpacing(fps: Double = 3000.0): Double? =
        estimatedAccuracyEnvelopeAtCurrentSpacing(fps)

    private fun accuracyEnvelopePercentRaw(
        splitNs: Double,
        gateM: Double,
        measurementErrorM: Double,
    ): Double {
        val hw = ble.hwInfo.value ?: HwInfo.DEFAULT
        val tickNs = hw.tickPs / 1000.0 / sqrt(12.0)
        val jitterNs = hw.edgeJitterNs * sqrt(2.0)             // two independent edges
        val clockNs = splitNs * hw.clockPpm / 1_000_000.0
        val frontEndNs = calibrationFrontEndNs()
        val sigmaT = sqrt(
            tickNs.pow(2.0) + jitterNs.pow(2.0) + clockNs.pow(2.0) + frontEndNs.pow(2.0)
        )
        // measurementErrorM is already a user-entered +/- bound, so do not expand
        // it by 2.58 again. Combine it with the 99% timing envelope as an
        // independent relative velocity contribution.
        val timingEnvelopeRel = 2.58 * sigmaT / splitNs
        val spacingEnvelopeRel = measurementErrorM.coerceAtLeast(0.0) / gateM
        return sqrt(timingEnvelopeRel.pow(2.0) + spacingEnvelopeRel.pow(2.0)) * 100.0
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

    fun saveDistance(
        value: Double,
        unit: DistanceUnit,
        measurementError: Double,
        errorUnit: DistanceUnit,
    ) {
        distanceValue = value
        distanceUnit = unit
        measurementErrorValue = measurementError
        measurementErrorUnit = errorUnit
        ble.simDistanceM = distanceM
        prefs.edit()
            .putFloat("distanceValue", value.toFloat())
            .putString("unit", unit.name)
            .putFloat("measurementErrorValue", measurementError.toFloat())
            .putString("measurementErrorUnit", errorUnit.name)
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
    fun checkPorts() {
        portCheckRequested = true
        ble.sendCommand(Proto.CMD_HEALTH)
    }
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

    /** Start a new test and re-run the whole wizard from a bare-port baseline. */
    fun redoSetup() {
        session.beginNewTest()
        pendingLabel = session.suggestedLabel()
        setupResultRecorded = false
        prefs.edit().putBoolean(setupResultKey(), false).apply()
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
        runCatching {
            getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        }
        ble.disconnect()
    }
}
