package com.chrono.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Mirrors the protocol defined in firmware/Chronograph/Chronograph.ino */
object Proto {
    val SERVICE: UUID = UUID.fromString("a5c40001-9d95-4e4c-8c5a-c1d6f2a80de1")
    val STATUS: UUID = UUID.fromString("a5c40002-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CONTROL: UUID = UUID.fromString("a5c40003-9d95-4e4c-8c5a-c1d6f2a80de1")
    val RESULT: UUID = UUID.fromString("a5c40004-9d95-4e4c-8c5a-c1d6f2a80de1")
    val TIME: UUID = UUID.fromString("a5c40005-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CAL: UUID = UUID.fromString("a5c40006-9d95-4e4c-8c5a-c1d6f2a80de1")
    val INFO: UUID = UUID.fromString("a5c40007-9d95-4e4c-8c5a-c1d6f2a80de1")
    val HEALTH: UUID = UUID.fromString("a5c40008-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_VERIFY1: Byte = 1
    const val CMD_VERIFY2: Byte = 2
    const val CMD_ARM: Byte = 3
    const val CMD_DISARM: Byte = 4
    const val CMD_ACK: Byte = 5
    const val CMD_CANCEL: Byte = 6
    const val CMD_FETCH: Byte = 7
    const val CMD_CALIBRATE: Byte = 8
    const val CMD_HEALTH: Byte = 9
    const val CMD_IDENTIFY: Byte = 10
    const val CMD_ARM_OVERRIDE: Byte = 11

    const val ST_IDLE = 0
    const val ST_VERIFY1 = 1
    const val ST_VERIFY1_OK = 2
    const val ST_VERIFY2 = 3
    const val ST_VERIFY2_OK = 4
    const val ST_ARMED = 5
    const val ST_RUNNING = 6
    const val ST_CALIBRATING = 7
    const val ST_CHECKING = 8
    const val ST_FAULT = 9

    const val PORT_STUCK_HIGH = 1
    const val PORT_LEAK_OR_SHORT = 2
    const val PORT_UNSTABLE = 4
    const val PORT_CROSS_COUPLED = 8
    const val PORT_MISSING_SENSOR = 16

    const val RESULT_TIME_VALID = 1
    const val RESULT_ARM_OVERRIDE = 2
    const val RESULT_STOP_BEFORE_START = 4
    const val RESULT_STOP_TIMEOUT = 8
    const val RESULT_SPLIT_TOO_SHORT = 16
    const val RESULT_SPLIT_TOO_LONG = 32
    const val RESULT_PORT_WARNING = 64
    const val RESULT_CLOCK_FAULT = 128
}

data class DeviceStatus(
    val state: Int,
    val pendingCount: Int,
    val timeValid: Boolean,
    val batteryPercent: Int? = null,
    val batteryMv: Int? = null,
) {
    val lowBattery: Boolean
        get() = (batteryPercent != null && batteryPercent <= 15) ||
            (batteryMv != null && batteryMv in 1..3499)
}

data class RawResult(
    val id: Int,
    val splitNs: Long,
    val epochSec: Long,
    val flags: Int = 0,
    val startTicks: Long = 0,
    val stopTicks: Long = 0,
    val batteryMv: Int? = null,
    val portFlags: Int = 0,
    val bootId: Long = 0,
    val resetCause: Long = 0,
    val hwRev: Int = 0,
    val fwMajor: Int = 0,
    val fwMinor: Int = 0,
    val formatVersion: Int = 1,
    val crcValid: Boolean = true,
)

data class PortHealth(val flags: Int, val signatureNs: Long) {
    val serious: Boolean get() = flags and (
        Proto.PORT_STUCK_HIGH or Proto.PORT_LEAK_OR_SHORT or
            Proto.PORT_CROSS_COUPLED or Proto.PORT_MISSING_SENSOR
        ) != 0

    fun summary(): String = when {
        flags and Proto.PORT_STUCK_HIGH != 0 -> "Input stuck high"
        flags and Proto.PORT_CROSS_COUPLED != 0 -> "Cross-channel connection suspected"
        flags and Proto.PORT_LEAK_OR_SHORT != 0 -> "Conductive leakage or short suspected"
        flags and Proto.PORT_MISSING_SENSOR != 0 -> "Sensor not detected"
        flags and Proto.PORT_UNSTABLE != 0 -> "Ready; variable signature"
        else -> "Ready"
    }
}

data class HealthStatus(
    val channel1: PortHealth,
    val channel2: PortHealth,
    val checkedAtBootMs: Long,
) {
    val ready: Boolean get() = checkedAtBootMs > 0 && !channel1.serious && !channel2.serious
}

enum class SimFault(val label: String) {
    NONE("No fault"),
    STUCK_HIGH_1("CH1 stuck high"),
    SHORT_GROUND_1("CH1 leakage/short"),
    UNSTABLE_1("CH1 unstable"),
    CROSS_CHANNEL("Channels coupled"),
    MISSING_1("CH1 sensor missing"),
    MISSING_2("CH2 sensor missing"),
    STOP_BEFORE_START("STOP before START"),
    STOP_TIMEOUT("STOP timeout"),
    SPLIT_OUT_OF_RANGE("Impossible split"),
}

/**
 * Self-reported identity and timing spec of the connected hardware. The app
 * derives its accuracy model from these numbers, so a future revision that
 * reports a finer tick or lower jitter automatically shows tighter confidence.
 */
data class HwInfo(
    val hwRev: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val tickPs: Long,         // timer tick period, picoseconds
    val clockPpm: Int,        // crystal tolerance
    val edgeJitterNs: Int,    // per-edge front-end uncertainty
    val mcuSerial: String = "",
    val channelCount: Int = 2,
    val inputStage: Int = 1,
    val capabilities: Int = 0,
) {
    companion object {
        /** Assumed when the device predates the INFO characteristic. */
        val DEFAULT = HwInfo(1, 1, 0, 62_500, 30, 300)
    }
}

/** One calibration sweep summary from the device (all times in ns). */
data class CalReading(
    val channel: Int,
    val status: Int,      // 0 ok, 1 some timeouts, 2 failed
    val samples: Int,
    val medianNs: Long,
    val meanNs: Long,
    val stddevNs: Long,
    val minNs: Long,
)

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int)

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Handles all BluetoothGatt plumbing: scanning, connecting, a serialized
 * operation queue (BLE allows only one GATT operation in flight), and
 * automatic reconnection when the link drops mid-test.
 */
@SuppressLint("MissingPermission") // permissions are requested before any screen can reach this class
class ChronoBle(private val context: Context) {

    val connState = MutableStateFlow(ConnState.DISCONNECTED)
    val status = MutableStateFlow<DeviceStatus?>(null)
    val results = MutableSharedFlow<RawResult>(extraBufferCapacity = 32)
    val cal = MutableSharedFlow<CalReading>(extraBufferCapacity = 8)
    val hwInfo = MutableStateFlow<HwInfo?>(null)
    val health = MutableStateFlow<HealthStatus?>(null)
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var userDisconnected = false
    private val prefs = context.getSharedPreferences("chrono_ble", Context.MODE_PRIVATE)

    private var chControl: BluetoothGattCharacteristic? = null
    private var chStatus: BluetoothGattCharacteristic? = null
    private var chResult: BluetoothGattCharacteristic? = null
    private var chTime: BluetoothGattCharacteristic? = null
    private var chCal: BluetoothGattCharacteristic? = null
    private var chInfo: BluetoothGattCharacteristic? = null
    private var chHealth: BluetoothGattCharacteristic? = null
    private var smoothedBatteryMv: Int? = null
    private var smoothedBatteryPercent: Int? = null

    val deviceStorageKey: String
        get() = if (isSimulation) "SIM-0001"
        else hwInfo.value?.mcuSerial?.takeIf { it.isNotBlank() }
            ?: prefs.getString("lastDeviceAddress", "unknown")!!.replace(":", "")

    fun nicknameFor(address: String): String = prefs.getString("nickname_$address", "").orEmpty()
    fun setNickname(address: String, nickname: String) {
        prefs.edit().putString("nickname_$address", nickname.trim()).apply()
        found.value = found.value.toList()
    }

    fun wasLastConnected(address: String): Boolean =
        prefs.getString("lastSuccessfulAddress", null) == address

    // ------------------------------------------------------------ scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Chrono"
            val rest = found.value.filter { it.device.address != result.device.address }
            found.value = (rest + FoundDevice(result.device, name, result.rssi))
                .sortedByDescending { it.rssi }
        }
    }

    fun startScan() {
        val ad = adapter ?: return
        if (!ad.isEnabled) return
        found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { ad.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCb) }
        connState.value = ConnState.SCANNING
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        if (connState.value == ConnState.SCANNING) connState.value = ConnState.DISCONNECTED
    }

    // ---------------------------------------------------------- connection

    fun connect(device: BluetoothDevice) {
        stopScan()
        reconnectJob?.cancel()
        runCatching { gatt?.close() }
        prefs.edit().putString("lastDeviceAddress", device.address).apply()
        userDisconnected = false
        connState.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun reconnectLast(): Boolean {
        val address = prefs.getString("lastDeviceAddress", null) ?: return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        userDisconnected = false
        scheduleReconnect(device)
        return true
    }

    fun disconnect() {
        reconnectJob?.cancel()
        if (isSimulation) {
            simJob?.cancel()
            isSimulation = false
            status.value = null
            hwInfo.value = null
            health.value = null
            connState.value = ConnState.DISCONNECTED
            return
        }
        userDisconnected = true
        synchronized(opLock) { opQueue.clear(); opInFlight = false }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        status.value = null
        hwInfo.value = null
        health.value = null
        smoothedBatteryMv = null
        smoothedBatteryPercent = null
        connState.value = ConnState.DISCONNECTED
    }

    // ------------------------------------------------------------ simulation
    // A fake device so the whole UI can be exercised with no hardware. It
    // drives the same status/result flows the real GATT path does, so every
    // screen behaves identically — verify steps auto-acknowledge, ARM produces
    // a realistic shot, and signal loss can be triggered on demand.

    var isSimulation = false
        private set

    /** Gate spacing in meters, kept in sync by the ViewModel so simulated
     *  splits map to believable velocities regardless of the configured gap. */
    var simDistanceM: Double = 0.1524   // 6 in default

    private val simScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var simJob: Job? = null
    private val bleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var simState = Proto.ST_IDLE
    private var simPending = 0
    private var simTimeValid = false
    private var simNextId = 1
    private var simBarePhase = true
    var simFault = SimFault.NONE
        private set
    private val simBufferedResults = mutableListOf<RawResult>()

    fun connectSimulated() {
        stopScan()
        reconnectJob?.cancel()
        userDisconnected = true
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        isSimulation = true
        simState = Proto.ST_IDLE
        simPending = 0
        simTimeValid = false
        simBarePhase = true
        simBufferedResults.clear()
        hwInfo.value = HwInfo(2, 2, 0, 62_500, 30, 300, "SIM-0001", 2, 1, 0x0007)
        setSimFault(SimFault.NONE)
        pushSimStatus()
        connState.value = ConnState.CONNECTED
    }

    private fun scheduleReconnect(device: BluetoothDevice) {
        if (userDisconnected) return
        reconnectJob?.cancel()
        connState.value = ConnState.RECONNECTING
        reconnectJob = bleScope.launch {
            while (!userDisconnected && connState.value != ConnState.CONNECTED) {
                synchronized(opLock) { opQueue.clear(); opInFlight = false }
                runCatching { gatt?.close() }
                gatt = runCatching {
                    device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                delay(4_000)
            }
        }
    }

    /** Toggle the link for testing: drop to RECONNECTING, tap again to restore. */
    fun simulateSignalLoss() {
        if (!isSimulation) return
        if (connState.value == ConnState.RECONNECTING) {
            connState.value = ConnState.CONNECTED
            flushSimBufferedResults()
        } else {
            connState.value = ConnState.RECONNECTING
        }
    }

    private fun pushSimStatus() {
        status.value = DeviceStatus(simState, simPending, simTimeValid, batteryPercent = 82, batteryMv = 3990)
    }

    fun setSimFault(fault: SimFault) {
        simFault = fault
        val flags1 = when (fault) {
            SimFault.STUCK_HIGH_1 -> Proto.PORT_STUCK_HIGH
            SimFault.SHORT_GROUND_1 -> Proto.PORT_LEAK_OR_SHORT
            SimFault.UNSTABLE_1 -> Proto.PORT_UNSTABLE
            SimFault.CROSS_CHANNEL -> Proto.PORT_CROSS_COUPLED
            SimFault.MISSING_1 -> Proto.PORT_MISSING_SENSOR
            else -> 0
        }
        val flags2 = when (fault) {
            SimFault.CROSS_CHANNEL -> Proto.PORT_CROSS_COUPLED
            SimFault.MISSING_2 -> Proto.PORT_MISSING_SENSOR
            else -> 0
        }
        health.value = HealthStatus(
            PortHealth(flags1, if (flags1 and Proto.PORT_MISSING_SENSOR != 0) 800 else 205_000),
            PortHealth(flags2, if (flags2 and Proto.PORT_MISSING_SENSOR != 0) 900 else 211_000),
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    private fun simCommand(cmd: Byte, arg: Int) {
        when (cmd) {
            Proto.CMD_VERIFY1 -> simVerify(1)
            Proto.CMD_VERIFY2 -> simVerify(2)
            Proto.CMD_ARM -> simArm()
            Proto.CMD_ARM_OVERRIDE -> simArm(overrideFaults = true)
            Proto.CMD_DISARM, Proto.CMD_CANCEL -> {
                simJob?.cancel()
                simState = Proto.ST_IDLE
                pushSimStatus()
            }
            Proto.CMD_ACK -> {
                if (simPending > 0) simPending--
                pushSimStatus()
            }
            Proto.CMD_FETCH -> flushSimBufferedResults()
            Proto.CMD_CALIBRATE -> simCalibrate(arg)
            Proto.CMD_HEALTH -> { setSimFault(simFault); pushSimStatus() }
            Proto.CMD_IDENTIFY -> Unit
        }
    }

    /**
     * The app tells the sim whether the upcoming sweep is a bare-port baseline
     * or a loaded (sensor-attached) measurement, so the loaded value is always
     * higher than the baseline regardless of how many times setup is redone.
     */
    fun setSimCalPhase(bare: Boolean) { simBarePhase = bare }

    private fun simCalibrate(channel: Int) {
        if (channel !in 1..2) return
        val bare = simBarePhase
        simScope.launch {
            val prev = simState
            simState = Proto.ST_CALIBRATING
            pushSimStatus()
            delay(900)
            val base = if (bare) 1_800L + channel * 80L else 205_000L + channel * 4_000L
            val median = base + (-25L..25L).random()
            cal.tryEmit(
                CalReading(
                    channel = channel, status = 0, samples = 64,
                    medianNs = median, meanNs = median + 4,
                    stddevNs = 35L + (0L..20L).random(), minNs = median - 60,
                )
            )
            simState = prev
            pushSimStatus()
        }
    }

    private fun simVerify(sensor: Int) {
        simJob?.cancel()
        simState = if (sensor == 1) Proto.ST_VERIFY1 else Proto.ST_VERIFY2
        pushSimStatus()
        // Deliberately does NOT auto-complete: the user presses the app's
        // "Simulate sensor tap" button, mirroring the real physical tap.
    }

    /** Sim stand-in for physically tapping the sensor under test. */
    fun simulateSensorTap() {
        if (!isSimulation) return
        when (simState) {
            Proto.ST_VERIFY1 -> { simState = Proto.ST_VERIFY1_OK; pushSimStatus() }
            Proto.ST_VERIFY2 -> { simState = Proto.ST_VERIFY2_OK; pushSimStatus() }
            else -> Unit
        }
    }

    private fun simArm(overrideFaults: Boolean = false) {
        simJob?.cancel()
        setSimFault(simFault)
        if (health.value?.ready == false && !overrideFaults) {
            simState = Proto.ST_FAULT
            pushSimStatus()
            return
        }
        simState = Proto.ST_ARMED
        pushSimStatus()
        simJob = simScope.launch {
            delay(1800)                    // waiting for the shot
            if (simFault == SimFault.STOP_BEFORE_START) {
                emitSimFaultResult(Proto.RESULT_STOP_BEFORE_START)
                return@launch
            }
            simState = Proto.ST_RUNNING
            pushSimStatus()
            if (simFault == SimFault.STOP_TIMEOUT) {
                delay(1000)
                emitSimFaultResult(Proto.RESULT_STOP_TIMEOUT)
                return@launch
            }
            delay(350)                     // shot in flight
            val split = if (simFault == SimFault.SPLIT_OUT_OF_RANGE) 5_000L else simSplitNs()
            val epoch = if (simTimeValid) System.currentTimeMillis() / 1000L else 0L
            simPending++
            pushSimStatus()
            val flags = (if (overrideFaults) Proto.RESULT_ARM_OVERRIDE else 0) or
                (if (health.value?.ready == false) Proto.RESULT_PORT_WARNING else 0) or
                (if (split < 10_000L) Proto.RESULT_SPLIT_TOO_SHORT else 0)
            val result = RawResult(simNextId++, split, epoch, flags = flags,
                startTicks = 1000, stopTicks = 1000 + split / 62, batteryMv = 3990,
                bootId = 0x53494D31, hwRev = 2, fwMajor = 2, fwMinor = 5,
                formatVersion = 2)
            if (connState.value == ConnState.CONNECTED) results.tryEmit(result)
            else simBufferedResults.add(result)
            simState = Proto.ST_IDLE
            pushSimStatus()
        }
    }

    private fun emitSimFaultResult(flag: Int) {
        val epoch = if (simTimeValid) System.currentTimeMillis() / 1000L else 0L
        simPending++
        val reversed = flag == Proto.RESULT_STOP_BEFORE_START
        val split = if (reversed) 25_000_000L else 0L
        val result = RawResult(simNextId++, split, epoch, flags = flag,
            startTicks = if (reversed) 401_000 else if (flag == Proto.RESULT_STOP_TIMEOUT) 1000 else 0,
            stopTicks = if (reversed) 1000 else 0,
            batteryMv = 3990, bootId = 0x53494D31, hwRev = 2, fwMajor = 2,
            fwMinor = 5, formatVersion = 2)
        if (connState.value == ConnState.CONNECTED) results.tryEmit(result)
        else simBufferedResults.add(result)
        simState = Proto.ST_FAULT
        pushSimStatus()
    }

    private fun flushSimBufferedResults() {
        if (!isSimulation || simBufferedResults.isEmpty()) return
        val pending = simBufferedResults.toList()
        simBufferedResults.clear()
        for (r in pending) results.tryEmit(r)
        pushSimStatus()
    }

    /** A split that yields a random, realistic muzzle velocity for the set gap. */
    private fun simSplitNs(): Long {
        val fps = (900..3200).random().toDouble()
        val mps = fps / 3.28084
        val d = if (simDistanceM > 0) simDistanceM else 0.1524
        val seconds = d / mps
        return (seconds * 1_000_000_000.0).toLong().coerceAtLeast(1)
    }

    // ---------------------------------------- serialized GATT operation queue

    private val opLock = Any()
    private val opQueue = ArrayDeque<() -> Boolean>()
    private var opInFlight = false

    /** Queue a GATT call. The op returns false if it failed to start; then we move on. */
    private fun enqueue(op: () -> Boolean) {
        synchronized(opLock) {
            opQueue.addLast(op)
            if (!opInFlight) pump()
        }
    }

    private fun pump() {
        while (true) {
            val op = opQueue.removeFirstOrNull() ?: run { opInFlight = false; return }
            opInFlight = true
            val startedOk = runCatching(op).getOrDefault(false)
            if (startedOk) return // wait for the matching onXxx callback -> opDone()
            // op failed to start; try the next one
        }
    }

    private fun opDone() {
        synchronized(opLock) {
            opInFlight = false
            pump()
        }
    }

    // ------------------------------------------------------------- commands

    @Suppress("DEPRECATION")
    fun sendCommand(cmd: Byte, arg: Int = 0) {
        if (isSimulation) { simCommand(cmd, arg); return }
        val payload = byteArrayOf(cmd, (arg and 0xFF).toByte(), ((arg shr 8) and 0xFF).toByte())
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chControl ?: return@enqueue false
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = payload
            g.writeCharacteristic(c)
        }
    }

    /** Push the phone's current time to the device (unix seconds, little-endian). */
    @Suppress("DEPRECATION")
    fun syncTime() {
        if (isSimulation) { simTimeValid = true; pushSimStatus(); return }
        val epoch = (System.currentTimeMillis() / 1000L).toInt()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(epoch).array()
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chTime ?: return@enqueue false
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = payload
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(c: BluetoothGattCharacteristic) {
        enqueue {
            val g = gatt ?: return@enqueue false
            if (!g.setCharacteristicNotification(c, true)) return@enqueue false
            val d = c.getDescriptor(Proto.CCCD) ?: return@enqueue false
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    private fun readStatus() {
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chStatus ?: return@enqueue false
            g.readCharacteristic(c)
        }
    }

    private fun readInfo() {
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chInfo ?: return@enqueue false
            g.readCharacteristic(c)
        }
    }

    private fun readHealth() {
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chHealth ?: return@enqueue false
            g.readCharacteristic(c)
        }
    }

    // ------------------------------------------------------- GATT callbacks

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectJob?.cancel()
                    g.requestMtu(185)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(opLock) { opQueue.clear(); opInFlight = false }
                    if (userDisconnected) {
                        g.close()
                        gatt = null
                        connState.value = ConnState.DISCONNECTED
                    } else {
                        // Expected during test standby: reconnect automatically
                        // with visible state and explicit retries.
                        val device = g.device
                        g.close()
                        scheduleReconnect(device)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Proto.SERVICE)
            if (svc == null) {
                g.disconnect()
                return
            }
            chStatus = svc.getCharacteristic(Proto.STATUS)
            chControl = svc.getCharacteristic(Proto.CONTROL)
            chResult = svc.getCharacteristic(Proto.RESULT)
            chTime = svc.getCharacteristic(Proto.TIME)
            chCal = svc.getCharacteristic(Proto.CAL)    // optional (newer firmware)
            chInfo = svc.getCharacteristic(Proto.INFO)  // optional (newer firmware)
            chHealth = svc.getCharacteristic(Proto.HEALTH) // optional (protocol v2)
            if (chStatus == null || chControl == null || chResult == null || chTime == null) {
                g.disconnect()
                return
            }
            enableNotifications(chStatus!!)
            enableNotifications(chResult!!)
            chCal?.let { enableNotifications(it) }
            chHealth?.let { enableNotifications(it) }
            readStatus()
            readInfo()
            readHealth()
            syncTime()                        // sync clock on every (re)connect
            sendCommand(Proto.CMD_FETCH)      // collect results recorded while disconnected
            enqueue {
                prefs.edit()
                    .putString("lastSuccessfulAddress", g.device.address)
                    .putLong("lastConnectedAt_${g.device.address}", System.currentTimeMillis())
                    .apply()
                connState.value = ConnState.CONNECTED
                false // action-only op: report "didn't start a GATT call" so the queue moves on
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone()

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) c.value?.let { handleValue(c.uuid, it) }
            opDone()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { handleValue(c.uuid, it) }
        }
    }

    private fun handleValue(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Proto.STATUS -> if (v.size >= 3) {
                val reportedPercent = if (v.size >= 4) (v[3].toInt() and 0xFF)
                    .takeUnless { it == 0xFF } else null
                val rawBatteryMv = if (v.size >= 6) {
                    (ByteBuffer.wrap(v, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
                        .takeUnless { it == 0xFFFF }
                } else null
                val batteryMv = rawBatteryMv?.let { raw ->
                    val previous = smoothedBatteryMv
                    (if (previous == null) raw else ((previous * 4L + raw) / 5L).toInt())
                        .also { smoothedBatteryMv = it }
                }
                val targetPercent = batteryMv?.let(::batteryPercentFromMv) ?: reportedPercent
                val batteryPercent = targetPercent?.let { target ->
                    val previous = smoothedBatteryPercent
                    when {
                        previous == null -> target
                        target > previous + 2 -> target
                        target < previous - 2 -> target
                        else -> previous
                    }.also { smoothedBatteryPercent = it }
                }
                status.value = DeviceStatus(
                    state = v[0].toInt() and 0xFF,
                    pendingCount = v[1].toInt() and 0xFF,
                    timeValid = v[2].toInt() != 0,
                    batteryPercent = batteryPercent,
                    batteryMv = batteryMv,
                )
            }
            Proto.RESULT -> if (v.size >= 11) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val id = b.short.toInt() and 0xFFFF
                val splitNs = b.int.toLong() and 0xFFFFFFFFL
                val epochSec = b.int.toLong() and 0xFFFFFFFFL
                val flags = b.get().toInt() and 0xFF
                if (v.size >= 37) {
                    val startTicks = b.int.toLong() and 0xFFFFFFFFL
                    val stopTicks = b.int.toLong() and 0xFFFFFFFFL
                    val batteryMv = (b.short.toInt() and 0xFFFF).takeUnless { it == 0xFFFF }
                    val portFlags = b.short.toInt() and 0xFFFF
                    val bootId = b.int.toLong() and 0xFFFFFFFFL
                    val resetCause = b.int.toLong() and 0xFFFFFFFFL
                    val hwRev = b.get().toInt() and 0xFF
                    val fwMajor = b.get().toInt() and 0xFF
                    val fwMinor = b.get().toInt() and 0xFF
                    val formatVersion = b.get().toInt() and 0xFF
                    val packetCrc = b.short.toInt() and 0xFFFF
                    results.tryEmit(
                        RawResult(id, splitNs, epochSec, flags, startTicks, stopTicks,
                            batteryMv, portFlags, bootId, resetCause, hwRev, fwMajor,
                            fwMinor, formatVersion, packetCrc == crc16Ccitt(v, 35))
                    )
                } else {
                    results.tryEmit(RawResult(id, splitNs, epochSec, flags))
                }
            }
            Proto.CAL -> if (v.size >= 20) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val channel = b.get().toInt() and 0xFF
                val calStatus = b.get().toInt() and 0xFF
                val samples = b.short.toInt() and 0xFFFF
                val medianNs = b.int.toLong() and 0xFFFFFFFFL
                val meanNs = b.int.toLong() and 0xFFFFFFFFL
                val stddevNs = b.int.toLong() and 0xFFFFFFFFL
                val minNs = b.int.toLong() and 0xFFFFFFFFL
                cal.tryEmit(CalReading(channel, calStatus, samples, medianNs, meanNs, stddevNs, minNs))
            }
            Proto.INFO -> if (v.size >= 12) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val hwRev = b.get().toInt() and 0xFF
                val fwMajor = b.get().toInt() and 0xFF
                val fwMinor = b.get().toInt() and 0xFF
                b.get()   // reserved
                val tickPs = b.int.toLong() and 0xFFFFFFFFL
                val clockPpm = b.short.toInt() and 0xFFFF
                val edgeJitterNs = b.short.toInt() and 0xFFFF
                val serial = if (v.size >= 20) {
                    val id0 = b.int.toLong() and 0xFFFFFFFFL
                    val id1 = b.int.toLong() and 0xFFFFFFFFL
                    "%08X%08X".format(id1, id0)
                } else ""
                val channelCount = if (v.size >= 21) b.get().toInt() and 0xFF else 2
                val inputStage = if (v.size >= 22) b.get().toInt() and 0xFF else 1
                val capabilities = if (v.size >= 24) b.short.toInt() and 0xFFFF else 0
                hwInfo.value = HwInfo(hwRev, fwMajor, fwMinor, tickPs, clockPpm,
                    edgeJitterNs, serial, channelCount, inputStage, capabilities)
            }
            Proto.HEALTH -> if (v.size >= 18) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                b.get() // packet version
                b.get() // channel count
                val flags1 = b.short.toInt() and 0xFFFF
                val flags2 = b.short.toInt() and 0xFFFF
                val signature1 = b.int.toLong() and 0xFFFFFFFFL
                val signature2 = b.int.toLong() and 0xFFFFFFFFL
                val checkedAt = b.int.toLong() and 0xFFFFFFFFL
                health.value = HealthStatus(
                    PortHealth(flags1, signature1), PortHealth(flags2, signature2), checkedAt
                )
            }
        }
    }

    private fun crc16Ccitt(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (index in 0 until length.coerceAtMost(data.size)) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun batteryPercentFromMv(mv: Int): Int = when {
        mv >= 4200 -> 100
        mv <= 3300 -> 0
        else -> (((mv - 3300) * 100L + 450L) / 900L).toInt().coerceIn(0, 100)
    }
}
