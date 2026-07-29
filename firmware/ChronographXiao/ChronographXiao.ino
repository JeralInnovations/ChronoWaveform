#define CHRONO_BOARD_PROFILE 2  // XIAO nRF52840, external D5 LED, 1S LiPo ADC

/*
 * Chrono — BLE chronograph firmware for the two-channel nRF52840 logger
 * ---------------------------------------------------------------------
 * Two piezo triggers act as START and STOP gates. The original first-rising-
 * edge split is retained, while every observable high/low transition is also
 * timestamped and delivered to the phone as a reviewable digital waveform.
 *
 * TIMING PATH — this is the whole point of the device, so it is done in
 * hardware, not software:
 *
 *   sensor pin -> GPIOTE (edge) -> PPI -> TIMER2 CAPTURE
 *
 * The edge freezes the 16 MHz timer value in hardware the instant it
 * arrives, with zero dependence on the CPU or the BLE SoftDevice. The
 * capture path is identical on both channels, so its fixed latency
 * cancels in (stop - start). A PPI "fork" disables each channel's own
 * first-edge capture on its first edge, so piezo ringing cannot overwrite the
 * automatic timestamp. A second PPI path keeps timestamping later transitions
 * for the diagnostic trace. The high-frequency crystal (HFXO) is requested while armed
 * so the timer runs at an accurate 16 MHz (62.5 ns/tick) rather than off
 * the ±1.5% internal RC oscillator.
 *
 * Resolution: 62.5 ns. The split is reported to the phone in NANOSECONDS
 * so none of that resolution is lost to rounding.
 *
 * Wiring (see README.md):
 *   SENSOR 1 (start): protected piezo output to D0
 *   SENSOR 2 (stop):  protected piezo output to D1
 *   Pins use the internal pull-DOWN; a trigger is any rising edge past the
 *   input threshold. Match the two channels (piezo, clamp, CABLE LENGTH).
 *
 * Supported builds:
 *   ChronographNiceNano/ChronographNiceNano.ino - nice!nano v2, 1S LiPo
 *   ChronographXiao/ChronographXiao.ino         - XIAO nRF52840, 1S LiPo
 *
 * Both use a Bluefruit-compatible nRF52840 Arduino core.
 */

// Arduino generates function prototypes before the later packet definitions.
// These forward declarations keep generated prototypes well-formed.
struct CalResult;
struct Pending;

#include <bluefruit.h>
#include <nrf_gpio.h>
#include <nrf_soc.h>

// ---------------------------------------------------------- board profile
// Build wrappers define this before including this file. The fallback preserves
// the original nice!nano behavior if this canonical sketch is opened directly.
#define CHRONO_BOARD_NICENANO 1
#define CHRONO_BOARD_XIAO     2
#ifndef CHRONO_BOARD_PROFILE
#define CHRONO_BOARD_PROFILE CHRONO_BOARD_NICENANO
#endif

#if CHRONO_BOARD_PROFILE == CHRONO_BOARD_XIAO
// Seeed XIAO nRF52840 non-mbed core battery-divider pins. The board's variant
// supplies these names; the fallbacks support older compatible package releases.
#ifndef PIN_VBAT
#define PIN_VBAT 32
#endif
#ifndef PIN_VBAT_ENABLE
#define PIN_VBAT_ENABLE 14
#endif
#endif

const uint8_t SENSOR1_PIN = 0;   // D0 - START
const uint8_t SENSOR2_PIN = 1;   // D1 - STOP
const uint8_t CHARGE1_PIN = 2;   // D2 -> 10k 1% -> sensor-1 node (calibration)
const uint8_t CHARGE2_PIN = 3;   // D3 -> 10k 1% -> sensor-2 node (calibration)
const uint8_t WAKE_BUTTON_PIN = 4;
const uint32_t POWER_LONG_PRESS_MS = 1500UL;
const uint32_t BUTTON_DEBOUNCE_MS = 30UL;

#if CHRONO_BOARD_PROFILE == CHRONO_BOARD_XIAO
const uint8_t HW_REV = 2;        // economical XIAO two-channel PTH board
const uint8_t STATUS_LED_PIN = 5;
void statusLedBegin() { pinMode(STATUS_LED_PIN, OUTPUT); digitalWrite(STATUS_LED_PIN, LOW); }
void statusLedWrite(bool on) { digitalWrite(STATUS_LED_PIN, on ? HIGH : LOW); }
#elif CHRONO_BOARD_PROFILE == CHRONO_BOARD_NICENANO
const uint8_t HW_REV = 1;        // original nice!nano prototype
const uint32_t STATUS_LED_GPIO = 15;
void statusLedBegin() {
  nrf_gpio_cfg(STATUS_LED_GPIO, NRF_GPIO_PIN_DIR_OUTPUT,
               NRF_GPIO_PIN_INPUT_DISCONNECT, NRF_GPIO_PIN_NOPULL,
               NRF_GPIO_PIN_H0H1, NRF_GPIO_PIN_NOSENSE);
  nrf_gpio_pin_clear(STATUS_LED_GPIO);
}
void statusLedWrite(bool on) { nrf_gpio_pin_write(STATUS_LED_GPIO, on ? 1 : 0); }
#else
#error "CHRONO_BOARD_PROFILE must be CHRONO_BOARD_NICENANO or CHRONO_BOARD_XIAO"
#endif

// Hardware channels for the capture path. The S140 SoftDevice reserves
// PPI channels 17-31 and groups 4-5, so low numbers are free for us.
uint8_t GPIOTE_S1 = 0;
uint8_t GPIOTE_S2 = 1;
uint32_t GPIOTE_MASK_S1 = 1UL << 0;
uint32_t GPIOTE_MASK_S2 = 1UL << 1;
const uint8_t PPI_S1     = 0;
const uint8_t PPI_S2     = 1;
const uint8_t PPI_TRACE_S1 = 2;
const uint8_t PPI_TRACE_S2 = 3;
const uint8_t PPI_GRP_S1 = 0;
const uint8_t PPI_GRP_S2 = 1;
const uint32_t TIMER_HZ = 16000000UL;   // TIMER2 @ 16 MHz, PRESCALER 0
const uint16_t MAX_TRACE_EVENTS = 256;
const uint32_t TRACE_CAPTURE_MS = 250UL;
const uint8_t TRACE_FORMAT_VERSION = 1;
const uint8_t TRACE_EVENTS_PER_CHUNK = 40;

enum : uint8_t {
  TRACE_OVERFLOW = 1 << 0,
  TRACE_EDGE_LOSS_SUSPECTED = 1 << 1,
  TRACE_STOP_ACTIVITY_BEFORE_START = 1 << 2,
};

struct __attribute__((packed)) TraceEvent {
  uint8_t tick0;     // 24-bit offset from traceBaseTicks, little-endian
  uint8_t tick1;
  uint8_t tick2;
  uint8_t meta;      // bit0 channel (0=CH1, 1=CH2), bit1 level after edge
};

// ------------------------------------------------------------ protocol
// Device states reported on the STATUS characteristic
enum : uint8_t {
  ST_IDLE       = 0,
  ST_VERIFY1    = 1,  // watching sensor 1 for a test trigger
  ST_VERIFY1_OK = 2,
  ST_VERIFY2    = 3,  // watching sensor 2 for a test trigger
  ST_VERIFY2_OK = 4,
  ST_ARMED      = 5,  // standby: waiting for sensor 1
  ST_RUNNING    = 6,  // sensor 1 tripped, waiting for sensor 2
  ST_CALIBRATING = 7, // measuring a channel's RC signature
  ST_CHECKING    = 8, // automatic pre-arm port checks
  ST_FAULT       = 9, // arm refused or timing fault
};

// Commands written to the CONTROL characteristic: [cmd, argLo, argHi]
enum : uint8_t {
  CMD_VERIFY1 = 1,  // enter sensor-1 test mode
  CMD_VERIFY2 = 2,  // enter sensor-2 test mode
  CMD_ARM     = 3,  // standby — wait for a shot
  CMD_DISARM  = 4,
  CMD_ACK     = 5,  // arg = result id; phone has stored it, delete here
  CMD_CANCEL  = 6,  // back to idle from any state
  CMD_FETCH   = 7,  // re-send every stored (un-acked) result
  CMD_CALIBRATE = 8, // arg = channel (1|2): run an RC calibration sweep
  CMD_HEALTH    = 9, // run both port checks without arming
  CMD_IDENTIFY  = 10,// flash this logger's status LED
  CMD_ARM_OVERRIDE = 11, // logged override of a port-health arm refusal
  CMD_FETCH_TRACE = 12,  // arg = result id; send retained waveform chunks
};

// 128-bit UUIDs. Base: A5C4xxxx-9D95-4E4C-8C5A-C1D6F2A80DE1
// (Bluefruit wants the bytes in little-endian order.)
#define CHRONO_UUID(id)                                              \
  { 0xE1, 0x0D, 0xA8, 0xF2, 0xD6, 0xC1, 0x5A, 0x8C,                  \
    0x4C, 0x4E, 0x95, 0x9D,                                          \
    (uint8_t)((id) & 0xFF), (uint8_t)((id) >> 8), 0xC4, 0xA5 }

const uint8_t UUID_SERVICE[16] = CHRONO_UUID(0x0001);
const uint8_t UUID_STATUS [16] = CHRONO_UUID(0x0002);
const uint8_t UUID_CONTROL[16] = CHRONO_UUID(0x0003);
const uint8_t UUID_RESULT [16] = CHRONO_UUID(0x0004);
const uint8_t UUID_TIME   [16] = CHRONO_UUID(0x0005);
const uint8_t UUID_CAL    [16] = CHRONO_UUID(0x0006);
const uint8_t UUID_INFO   [16] = CHRONO_UUID(0x0007);
const uint8_t UUID_HEALTH [16] = CHRONO_UUID(0x0008);
const uint8_t UUID_TRACE  [16] = CHRONO_UUID(0x0009);

BLEService        svc     (UUID_SERVICE);
BLECharacteristic chStatus (UUID_STATUS);   // read/notify: StatusPacket below
BLECharacteristic chControl(UUID_CONTROL);  // write: commands
BLECharacteristic chResult (UUID_RESULT);   // read/notify: Result struct below
BLECharacteristic chTime   (UUID_TIME);     // write: uint32 LE unix seconds
BLECharacteristic chCal    (UUID_CAL);      // read/notify: CalResult struct below
BLECharacteristic chInfo   (UUID_INFO);     // read: HwInfo struct below
BLECharacteristic chHealth (UUID_HEALTH);   // read/notify: HealthPacket below
BLECharacteristic chTrace  (UUID_TRACE);    // read/notify: chunked TraceEvent stream

// One measurement on the wire. 11 bytes LE — parsed byte-for-byte by the app.
struct __attribute__((packed)) Result {
  uint16_t id;
  uint32_t splitNs;   // time between sensor 1 and sensor 2, NANOSECONDS
  uint32_t epoch;     // unix seconds at the stop trigger, 0 = clock never synced
  uint8_t  flags;     // bit0: epoch is valid
  uint32_t startTicks;
  uint32_t stopTicks;
  uint16_t batteryMv;
  uint16_t portFlags; // low byte CH1, high byte CH2
  uint32_t bootId;
  uint32_t resetCause;
  uint8_t  hwRev;
  uint8_t  fwMajor;
  uint8_t  fwMinor;
  uint8_t  formatVersion;
  uint16_t crc16;
};

// Stored form: the timestamp is a boot-relative millis() reading — a
// "flywheel clock" that runs from power-on. The unix epoch is computed at
// SEND time, so a shot taken before the phone ever synced the clock still
// gets a correct timestamp as long as a sync happens at some point during
// this boot (it normally does, automatically, on every connect).
struct Pending {
  uint16_t id;
  uint32_t splitNs;
  uint32_t bootMs;
  uint8_t  flags;
  uint32_t startTicks;
  uint32_t stopTicks;
  uint16_t batteryMv;
  uint16_t portFlags;
  uint32_t traceBaseTicks;
  uint16_t traceCount;
  uint8_t  traceFlags;
  TraceEvent trace[MAX_TRACE_EVENTS];
};

// Results wait here until the phone ACKs them, so nothing is lost if
// the BLE link drops while a test is in progress.
const uint8_t MAX_PENDING = 16;
Pending  pending[MAX_PENDING];
uint8_t  pendingCount = 0;
uint16_t nextId       = 1;

// One calibration sweep summary. 20 bytes LE — fits a default-MTU notification.
struct __attribute__((packed)) CalResult {
  uint8_t  channel;   // 1 or 2
  uint8_t  status;    // 0 ok, 1 some samples timed out, 2 no valid samples
  uint16_t samples;   // valid samples included in the stats
  uint32_t medianNs;  // median time-to-threshold
  uint32_t meanNs;
  uint32_t stddevNs;
  uint32_t minNs;
};

// Identifies this hardware/firmware to the app so it can apply the right
// accuracy model. A future revision with tighter timing (e.g. a TDC front
// end) reports different numbers here and the app's confidence estimate
// follows automatically — no app update needed.
const uint8_t FW_MAJOR = 3;
const uint8_t FW_MINOR = 1;

enum : uint16_t {
  PORT_STUCK_HIGH = 1 << 0,
  PORT_LEAK_OR_SHORT = 1 << 1,
  PORT_UNSTABLE = 1 << 2,
  PORT_CROSS_COUPLED = 1 << 3,
  PORT_MISSING_SENSOR = 1 << 4,
};

enum : uint8_t {
  RESULT_TIME_VALID = 1 << 0,
  RESULT_ARM_OVERRIDE = 1 << 1,
  RESULT_STOP_BEFORE_START = 1 << 2,
  RESULT_STOP_TIMEOUT = 1 << 3,
  RESULT_SPLIT_TOO_SHORT = 1 << 4,
  RESULT_SPLIT_TOO_LONG = 1 << 5,
  RESULT_PORT_WARNING = 1 << 6,
  RESULT_CLOCK_FAULT = 1 << 7,
};

struct __attribute__((packed)) HealthPacket {
  uint8_t version;
  uint8_t channelCount;
  uint16_t channel1Flags;
  uint16_t channel2Flags;
  uint32_t channel1SignatureNs;
  uint32_t channel2SignatureNs;
  uint32_t checkedAtBootMs;
};

struct __attribute__((packed)) StatusPacket {
  uint8_t  state;
  uint8_t  pendingCount;
  uint8_t  timeValid;
  uint8_t  batteryPercent;
  uint16_t batteryMv;
  uint8_t  legacyBatteryLock; // retained for packet compatibility; always 0
};

struct __attribute__((packed)) HwInfo {
  uint8_t  hwRev;
  uint8_t  fwMajor;
  uint8_t  fwMinor;
  uint8_t  reserved;
  uint32_t tickPs;        // timer tick period in picoseconds (62500 = 62.5 ns)
  uint16_t clockPpm;      // crystal tolerance
  uint16_t edgeJitterNs;  // conservative per-edge front-end uncertainty
  uint32_t deviceId0;     // NRF_FICR->DEVICEID[0]
  uint32_t deviceId1;     // NRF_FICR->DEVICEID[1]
  uint8_t  channelCount;
  uint8_t  inputStage;    // 1 = direct protected GPIO
  uint16_t capabilities;  // bit0 health, bit1 identify, bit2 result-v2
};

// ------------------------------------------------------- run-time state
uint8_t  state    = ST_IDLE;
bool     armed    = false;
bool     started  = false;
bool     finished = false;

bool     fetchRequested = false;
volatile uint16_t traceFetchRequested = 0;
bool     hfxoOn         = false;
volatile uint8_t calRequested = 0;   // 1 or 2; handled in loop()
volatile uint8_t healthRequested = 0; // 1 normal, 2 logged override arm
uint32_t identifyUntilMs = 0;
bool powerButtonHolding = false;
uint32_t powerButtonPressedAtMs = 0;
uint32_t startedAtMs = 0;
uint32_t traceStartedAtMs = 0;
uint8_t activeResultFlags = 0;
volatile bool traceRecording = false;
volatile bool stopActivityBeforeStart = false;
volatile uint16_t activeTraceCount = 0;
volatile uint8_t activeTraceFlags = 0;
volatile uint32_t activeTraceBaseTicks = 0;
volatile uint8_t activeTraceLastLevel[2] = { 0, 0 };
TraceEvent activeTrace[MAX_TRACE_EVENTS];
HealthPacket health = { 1, 2, PORT_MISSING_SENSOR, PORT_MISSING_SENSOR, 0, 0, 0 };
uint32_t bootId = 0;
uint32_t resetCause = 0;

const uint32_t MIN_SENSOR_SIGNATURE_NS = 20000UL;
const uint32_t MAX_SENSOR_SIGNATURE_NS = 5000000UL;
const uint32_t MAX_STABLE_STDDEV_NS = 10000UL;
const uint8_t MIN_USABLE_HEALTH_SAMPLES = 48;
const uint8_t MIN_STABLE_HEALTH_SAMPLES = 60;
const uint32_t MIN_SPLIT_NS = 10000UL;
const uint32_t MAX_SPLIT_NS = 1000000000UL;
const uint32_t STOP_TIMEOUT_MS = 1000UL;

// ACK ids are queued here from the BLE callback and applied in loop(), so
// the pending[] buffer is only ever mutated from one context.
volatile uint16_t ackQueue[MAX_PENDING];
volatile uint8_t  ackHead = 0, ackTail = 0;
uint32_t lastBatteryNotifyMs = 0;
uint16_t filteredBatteryMv = 0;
bool batteryArmLocked = false;
uint32_t sensorGpio[2] = { 0, 0 };

// Wall-clock: the phone writes unix time; we extrapolate with millis().
bool     timeValid  = false;
uint32_t epochBase  = 0;
uint32_t millisBase = 0;

// --------------------------------------------------- hardware timing core
void appendTraceEvent(uint8_t channel, uint32_t ticks, bool level) {
  if (!traceRecording) return;

  uint16_t index = activeTraceCount;
  if (index >= MAX_TRACE_EVENTS) {
    activeTraceFlags |= TRACE_OVERFLOW;
    return;
  }

  if (index == 0) {
    activeTraceBaseTicks = ticks;
    traceStartedAtMs = millis();
  } else if (activeTraceLastLevel[channel] == (uint8_t)(level ? 1 : 0)) {
    // A valid digital waveform must alternate on each channel. Matching
    // consecutive levels means at least one transition happened before the
    // GPIOTE ISR copied its hardware-captured timestamp.
    activeTraceFlags |= TRACE_EDGE_LOSS_SUSPECTED;
  }

  uint32_t delta = ticks - activeTraceBaseTicks;
  if (delta > 0xFFFFFFUL) {
    activeTraceFlags |= TRACE_OVERFLOW;
    return;
  }

  TraceEvent& event = activeTrace[index];
  event.tick0 = (uint8_t)(delta & 0xFF);
  event.tick1 = (uint8_t)((delta >> 8) & 0xFF);
  event.tick2 = (uint8_t)((delta >> 16) & 0xFF);
  event.meta = (uint8_t)((channel & 1U) | (level ? 0x02U : 0U));
  activeTraceLastLevel[channel] = level ? 1 : 0;
  activeTraceCount = index + 1;

  if (channel == 1 && NRF_TIMER2->CC[0] == 0xFFFFFFFFUL) {
    stopActivityBeforeStart = true;
    activeTraceFlags |= TRACE_STOP_ACTIVITY_BEFORE_START;
  }
}

void onSensor1Change() {
  appendTraceEvent(0, NRF_TIMER2->CC[2], nrf_gpio_pin_read(sensorGpio[0]) != 0);
}

void onSensor2Change() {
  appendTraceEvent(1, NRF_TIMER2->CC[3], nrf_gpio_pin_read(sensorGpio[1]) != 0);
}

void setupTiming() {
  uint32_t p1 = g_ADigitalPinMap[SENSOR1_PIN];   // Arduino pin -> nRF pin number
  uint32_t p2 = g_ADigitalPinMap[SENSOR2_PIN];
  sensorGpio[0] = p1;
  sensorGpio[1] = p2;

  nrf_gpio_cfg_input(p1, NRF_GPIO_PIN_PULLDOWN);
  nrf_gpio_cfg_input(p2, NRF_GPIO_PIN_PULLDOWN);

  // Charge pins idle in true hi-Z (input buffer disconnected) so they never
  // load the sensor nodes during live fire.
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE1_PIN]);
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE2_PIN]);

  // TIMER2: free-running 32-bit counter at 16 MHz -> 62.5 ns/tick.
  NRF_TIMER2->TASKS_STOP  = 1;
  NRF_TIMER2->MODE        = TIMER_MODE_MODE_Timer;
  NRF_TIMER2->BITMODE     = TIMER_BITMODE_BITMODE_32Bit << TIMER_BITMODE_BITMODE_Pos;
  NRF_TIMER2->PRESCALER   = 0;
  NRF_TIMER2->TASKS_CLEAR = 1;
  NRF_TIMER2->TASKS_START = 1;

  // The Arduino core owns GPIOTE_IRQHandler. Register CHANGE callbacks so it
  // allocates compatible channels, then use those same hardware events for
  // PPI timestamp capture. Interrupts stay disabled outside live shots so the
  // existing calibration polling path remains deterministic.
  uint32_t mask1 = (uint32_t)attachInterrupt(SENSOR1_PIN, onSensor1Change, CHANGE);
  uint32_t mask2 = (uint32_t)attachInterrupt(SENSOR2_PIN, onSensor2Change, CHANGE);
  if (mask1) {
    GPIOTE_MASK_S1 = mask1;
    GPIOTE_S1 = (uint8_t)__builtin_ctz(mask1);
  }
  if (mask2) {
    GPIOTE_MASK_S2 = mask2;
    GPIOTE_S2 = (uint8_t)__builtin_ctz(mask2);
  }
  NRF_GPIOTE->INTENCLR = GPIOTE_MASK_S1 | GPIOTE_MASK_S2;

  // PPI: edge -> TIMER capture, and (fork) -> disable this channel's own
  // group so only the FIRST edge is captured (immune to piezo ringing).
  NRF_PPI->CH[PPI_S1].EEP   = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S1];
  NRF_PPI->CH[PPI_S1].TEP   = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[0];
  NRF_PPI->FORK[PPI_S1].TEP = (uint32_t)&NRF_PPI->TASKS_CHG[PPI_GRP_S1].DIS;

  NRF_PPI->CH[PPI_S2].EEP   = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S2];
  NRF_PPI->CH[PPI_S2].TEP   = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[1];
  NRF_PPI->FORK[PPI_S2].TEP = (uint32_t)&NRF_PPI->TASKS_CHG[PPI_GRP_S2].DIS;

  // Continuous trace path. These capture registers are copied by the GPIOTE
  // callbacks; unlike CC0/CC1 they intentionally update on every transition.
  NRF_PPI->CH[PPI_TRACE_S1].EEP = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S1];
  NRF_PPI->CH[PPI_TRACE_S1].TEP = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[2];
  NRF_PPI->CH[PPI_TRACE_S2].EEP = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S2];
  NRF_PPI->CH[PPI_TRACE_S2].TEP = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[3];
  NRF_PPI->CHG[PPI_GRP_S1] = (1UL << PPI_S1);
  NRF_PPI->CHG[PPI_GRP_S2] = (1UL << PPI_S2);
}

bool requestHfxo() {
  if (!hfxoOn) { sd_clock_hfclk_request(); hfxoOn = true; }
  uint32_t running = 0;
  uint32_t began = millis();
  do {
    sd_clock_hfclk_is_running(&running);
    if (running) return true;
    delay(1);
  } while ((uint32_t)(millis() - began) < 100UL);
  return false;
}
void releaseHfxo() {
  if (hfxoOn) { sd_clock_hfclk_release(); hfxoOn = false; }
}

// Arm both first-edge captures so reversed travel keeps hardware resolution.
bool armTiming() {
  if (!requestHfxo()) return false;
  NRF_GPIOTE->INTENCLR = GPIOTE_MASK_S1 | GPIOTE_MASK_S2;
  NRF_GPIOTE->EVENTS_IN[GPIOTE_S1] = 0;
  NRF_GPIOTE->EVENTS_IN[GPIOTE_S2] = 0;
  (void)NRF_GPIOTE->EVENTS_IN[GPIOTE_S2];     // flush the clears before continuing
  NRF_TIMER2->TASKS_CLEAR = 1;
  NRF_TIMER2->CC[0] = 0xFFFFFFFFUL;
  NRF_TIMER2->CC[1] = 0xFFFFFFFFUL;
  NRF_TIMER2->CC[2] = 0xFFFFFFFFUL;
  NRF_TIMER2->CC[3] = 0xFFFFFFFFUL;
  activeTraceCount = 0;
  activeTraceFlags = 0;
  activeTraceBaseTicks = 0;
  activeTraceLastLevel[0] = nrf_gpio_pin_read(sensorGpio[0]) ? 1 : 0;
  activeTraceLastLevel[1] = nrf_gpio_pin_read(sensorGpio[1]) ? 1 : 0;
  stopActivityBeforeStart = false;
  traceStartedAtMs = 0;
  traceRecording = true;
  NRF_PPI->CHENSET = (1UL << PPI_TRACE_S1) | (1UL << PPI_TRACE_S2);
  NRF_PPI->TASKS_CHG[PPI_GRP_S1].EN = 1;
  NRF_PPI->TASKS_CHG[PPI_GRP_S2].EN = 1;
  NRF_GPIOTE->INTENSET = GPIOTE_MASK_S1 | GPIOTE_MASK_S2;
  return true;
}

void disarmTiming() {
  traceRecording = false;
  NRF_GPIOTE->INTENCLR = GPIOTE_MASK_S1 | GPIOTE_MASK_S2;
  NRF_PPI->TASKS_CHG[PPI_GRP_S1].DIS = 1;
  NRF_PPI->TASKS_CHG[PPI_GRP_S2].DIS = 1;
  NRF_PPI->CHENCLR = (1UL << PPI_TRACE_S1) | (1UL << PPI_TRACE_S2);
  releaseHfxo();
}

// ----------------------------------------------------- calibration engine
// Measures a channel's time-to-threshold under a known stimulus: the charge
// pin steps HIGH through its 10 k 1% resistor and the node's RC rise is
// timed from a captured launch reference to the hardware GPIO threshold event.
// The charge step is a direct GPIO write; TIMER2 captures the threshold edge.
// This is a repeatable diagnostic signature, not precision capacitance. The app
// runs this with the port
// bare (baseline) and again with the sensor attached; the difference
// isolates the cable+sensor load with the pin threshold and fixture
// capacitance cancelled.

const uint8_t  CAL_SAMPLES       = 64;
const uint32_t CAL_DISCHARGE_US  = 1000;            // ~5 tau for a 200 nF piezo via 1k
const uint32_t CAL_TIMEOUT_TICKS = 16UL * 20000UL;  // 20 ms per sample

static uint32_t isqrt64(uint64_t v) {
  uint64_t r = 0, bit = 1ULL << 62;
  while (bit > v) bit >>= 2;
  while (bit) {
    if (v >= r + bit) { v -= r + bit; r = (r >> 1) + bit; }
    else r >>= 1;
    bit >>= 2;
  }
  return (uint32_t)r;
}

CalResult runCalibration(uint8_t channel, bool notify, bool* crossCoupled,
                         bool* initiallyHigh) {
  uint32_t sensPin = g_ADigitalPinMap[(channel == 1) ? SENSOR1_PIN : SENSOR2_PIN];
  uint32_t chgPin  = g_ADigitalPinMap[(channel == 1) ? CHARGE1_PIN : CHARGE2_PIN];
  uint8_t  evCh    = (channel == 1) ? GPIOTE_S1 : GPIOTE_S2;
  uint8_t  grp     = (channel == 1) ? PPI_GRP_S1 : PPI_GRP_S2;
  uint8_t  ccIdx   = (channel == 1) ? 0 : 1;
  uint32_t otherPin = g_ADigitalPinMap[(channel == 1) ? SENSOR2_PIN : SENSOR1_PIN];

  if (initiallyHigh) *initiallyHigh = nrf_gpio_pin_read(sensPin) != 0;
  if (crossCoupled) *crossCoupled = false;

  requestHfxo();   // absolute nanoseconds matter here: run off the crystal

  // Charge pin drives the node through 10k only during calibration. The
  // previous PPI/GPIOTE-task launch was elegant but brittle across board cores;
  // a direct GPIO step is plenty stable for setup calibration and much easier
  // to verify electrically.
  nrf_gpio_cfg_output(chgPin);
  nrf_gpio_pin_clear(chgPin);

  static uint32_t ticksBuf[CAL_SAMPLES];
  uint8_t valid = 0;
  uint8_t timeouts = 0;

  for (uint8_t i = 0; i <= CAL_SAMPLES; i++) {   // one extra: first sweep discarded
    // Discharge the node completely (sensor pin drives it low directly;
    // the charge pin pulls low through the 10k as well).
    nrf_gpio_pin_clear(chgPin);
    nrf_gpio_cfg(sensPin, NRF_GPIO_PIN_DIR_OUTPUT, NRF_GPIO_PIN_INPUT_CONNECT,
                 NRF_GPIO_PIN_NOPULL, NRF_GPIO_PIN_S0S1, NRF_GPIO_PIN_NOSENSE);
    nrf_gpio_pin_clear(sensPin);
    delayMicroseconds(CAL_DISCHARGE_US);

    // Release the node with NO pull — the internal pulldown would divide
    // the ramp against the 10k and sag the asymptote near the threshold.
    nrf_gpio_cfg(sensPin, NRF_GPIO_PIN_DIR_INPUT, NRF_GPIO_PIN_INPUT_CONNECT,
                 NRF_GPIO_PIN_NOPULL, NRF_GPIO_PIN_S0S1, NRF_GPIO_PIN_NOSENSE);

    NRF_GPIOTE->EVENTS_IN[evCh] = 0;
    (void)NRF_GPIOTE->EVENTS_IN[evCh];
    NRF_PPI->TASKS_CHG[grp].EN = 1;              // arm the threshold capture

    NRF_TIMER2->TASKS_CAPTURE[2] = 1;
    uint32_t t0 = NRF_TIMER2->CC[2];
    nrf_gpio_pin_set(chgPin);

    bool got = false;
    while (true) {
      if (NRF_GPIOTE->EVENTS_IN[evCh]) { got = true; break; }
      if (crossCoupled && nrf_gpio_pin_read(otherPin)) *crossCoupled = true;
      NRF_TIMER2->TASKS_CAPTURE[2] = 1;
      if ((uint32_t)(NRF_TIMER2->CC[2] - t0) > CAL_TIMEOUT_TICKS) break;
    }

    nrf_gpio_pin_clear(chgPin);
    NRF_PPI->TASKS_CHG[grp].DIS = 1;
    NRF_GPIOTE->EVENTS_IN[evCh] = 0;

    if (i == 0) continue;                        // residual-charge settling sweep
    if (got) ticksBuf[valid++] = NRF_TIMER2->CC[ccIdx] - t0;
    else     timeouts++;
  }

  // Restore live-fire pin configuration.
  nrf_gpio_pin_clear(chgPin);
  nrf_gpio_cfg_default(chgPin);                  // back to true hi-Z
  nrf_gpio_cfg_input(sensPin, NRF_GPIO_PIN_PULLDOWN);
  if (!armed) releaseHfxo();

  // Stats: insertion sort for the median, integer mean/stddev, all in ticks
  // then converted to ns (x62.5 = x125/2).
  for (uint8_t i = 1; i < valid; i++) {
    uint32_t key = ticksBuf[i];
    int8_t j = i - 1;
    while (j >= 0 && ticksBuf[j] > key) { ticksBuf[j + 1] = ticksBuf[j]; j--; }
    ticksBuf[j + 1] = key;
  }

  CalResult r = {};
  r.channel = channel;
  r.samples = valid;
  if (valid == 0) {
    r.status = 2;
  } else {
    r.status = (timeouts > 0) ? 1 : 0;
    uint64_t sum = 0, sumsq = 0;
    for (uint8_t i = 0; i < valid; i++) {
      sum += ticksBuf[i];
      sumsq += (uint64_t)ticksBuf[i] * ticksBuf[i];
    }
    uint32_t meanT = (uint32_t)(sum / valid);
    int64_t var = (int64_t)(sumsq / valid) - (int64_t)meanT * meanT;
    if (var < 0) var = 0;
    uint32_t medT = (valid & 1) ? ticksBuf[valid / 2]
                                : (ticksBuf[valid / 2 - 1] + ticksBuf[valid / 2]) / 2;
    r.medianNs = (uint32_t)(((uint64_t)medT * 125ULL) / 2ULL);
    r.meanNs   = (uint32_t)(((uint64_t)meanT * 125ULL) / 2ULL);
    r.stddevNs = (uint32_t)(((uint64_t)isqrt64((uint64_t)var) * 125ULL) / 2ULL);
    r.minNs    = (uint32_t)(((uint64_t)ticksBuf[0] * 125ULL) / 2ULL);
  }

  if (notify) {
    chCal.write((uint8_t*)&r, sizeof(r));
    chCal.notify((uint8_t*)&r, sizeof(r));
  }
  return r;
}

// -------------------------------------------------------------- helpers
// Flywheel conversion: boot-relative -> unix. The signed difference lets a
// sync applied AFTER a shot back-date it, and one applied before carry
// forward. Valid within ~24 days of the sync point (millis() wrap).
uint32_t epochForBootMs(uint32_t bootMs) {
  if (!timeValid) return 0;
  int32_t deltaMs = (int32_t)(bootMs - millisBase);
  return epochBase + deltaMs / 1000;
}

uint16_t readBatteryMv() {
#if CHRONO_BOARD_PROFILE == CHRONO_BOARD_NICENANO
  // VDDH is the battery/input domain on this nRF52 board package. The core
  // exposes its internal divide-by-five path, so no external ADC divider is
  // required for the one-cell LiPo reading.
  analogReadResolution(12);
  uint32_t raw = analogReadVDDHDIV5();
  uint32_t mv = (raw * 3600UL * 5UL + 2047UL) / 4095UL;
  if (mv > 65535UL) mv = 65535UL;
  return (uint16_t)mv;
#else
  // The XIAO battery pads feed its 1S LiPo charger and a 2.961:1 monitor
  // divider (1M/510k). LOW enables the divider; keeping it LOW also avoids
  // exposing P0.31 to the un-divided battery rail while USB charging.
  //
  // IMPORTANT: the divider's ~340k source impedance is far above what the
  // core's analogRead() 3 us acquisition window can charge, so it under-reads
  // by 10-20%. Read the channel directly with a 40 us acquisition
  // window and 8x hardware burst oversampling instead. (PIN_VBAT = P0.31 =
  // SAADC AIN7; gain 1/6 against the 0.6 V internal reference gives the same
  // 3.6 V full scale the old math assumed.)
  pinMode(PIN_VBAT_ENABLE, OUTPUT);
  digitalWrite(PIN_VBAT_ENABLE, LOW);
  pinMode(PIN_VBAT, INPUT);
  delay(2);                     // divider settle after enable

  NRF_SAADC->RESOLUTION = SAADC_RESOLUTION_VAL_12bit;
  NRF_SAADC->OVERSAMPLE = SAADC_OVERSAMPLE_OVERSAMPLE_Over8x;
  NRF_SAADC->CH[0].PSELP = SAADC_CH_PSELP_PSELP_AnalogInput7;
  NRF_SAADC->CH[0].PSELN = SAADC_CH_PSELN_PSELN_NC;
  NRF_SAADC->CH[0].CONFIG =
      (SAADC_CH_CONFIG_RESP_Bypass     << SAADC_CH_CONFIG_RESP_Pos) |
      (SAADC_CH_CONFIG_RESN_Bypass     << SAADC_CH_CONFIG_RESN_Pos) |
      (SAADC_CH_CONFIG_GAIN_Gain1_6    << SAADC_CH_CONFIG_GAIN_Pos) |
      (SAADC_CH_CONFIG_REFSEL_Internal << SAADC_CH_CONFIG_REFSEL_Pos) |
      (SAADC_CH_CONFIG_TACQ_40us       << SAADC_CH_CONFIG_TACQ_Pos) |
      (SAADC_CH_CONFIG_MODE_SE         << SAADC_CH_CONFIG_MODE_Pos) |
      (SAADC_CH_CONFIG_BURST_Enabled   << SAADC_CH_CONFIG_BURST_Pos);

  volatile int16_t sample = 0;
  NRF_SAADC->RESULT.PTR = (uint32_t)&sample;
  NRF_SAADC->RESULT.MAXCNT = 1;
  NRF_SAADC->ENABLE = SAADC_ENABLE_ENABLE_Enabled;
  NRF_SAADC->EVENTS_STARTED = 0;
  NRF_SAADC->TASKS_START = 1;
  while (!NRF_SAADC->EVENTS_STARTED) {}
  NRF_SAADC->EVENTS_END = 0;
  NRF_SAADC->TASKS_SAMPLE = 1;          // burst: 8 conversions, averaged in HW
  while (!NRF_SAADC->EVENTS_END) {}
  NRF_SAADC->EVENTS_STOPPED = 0;
  NRF_SAADC->TASKS_STOP = 1;
  while (!NRF_SAADC->EVENTS_STOPPED) {}
  NRF_SAADC->ENABLE = SAADC_ENABLE_ENABLE_Disabled;

  int32_t raw = sample;
  if (raw < 0) raw = 0;
  // 2.961 divider ratio * 3.6 V ADC range, converted to millivolts.
  uint32_t mv = ((uint32_t)raw * 10660UL + 2048UL) / 4096UL;
  return (uint16_t)mv;
#endif
}

uint8_t batteryPercentFromMv(uint16_t mv) {
  if (mv >= 4200) return 100;
  if (mv <= 3300) return 0;
  return (uint8_t)(((uint32_t)(mv - 3300) * 100UL + 450UL) / 900UL);
}

uint16_t sampleBatteryMv() {
  uint16_t raw = readBatteryMv();
  if (filteredBatteryMv == 0) filteredBatteryMv = raw;
  else filteredBatteryMv = (uint16_t)(((uint32_t)filteredBatteryMv * 7UL + raw + 4UL) / 8UL);
  return filteredBatteryMv;
}

uint16_t crc16Ccitt(const uint8_t* data, size_t len) {
  uint16_t crc = 0xFFFF;
  while (len--) {
    crc ^= (uint16_t)(*data++) << 8;
    for (uint8_t i = 0; i < 8; i++)
      crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
  }
  return crc;
}

uint32_t makeBootId() {
  uint32_t id = NRF_FICR->DEVICEID[0] ^ NRF_FICR->DEVICEID[1] ^ micros();
  NRF_RNG->TASKS_START = 1;
  for (uint8_t i = 0; i < 4; i++) {
    uint32_t began = millis();
    while (!NRF_RNG->EVENTS_VALRDY && (uint32_t)(millis() - began) < 20UL) {}
    if (NRF_RNG->EVENTS_VALRDY) {
      NRF_RNG->EVENTS_VALRDY = 0;
      id ^= (uint32_t)NRF_RNG->VALUE << (i * 8);
    }
  }
  NRF_RNG->TASKS_STOP = 1;
  return id;
}

uint16_t classifyPort(const CalResult& r, bool crossCoupled, bool initiallyHigh) {
  uint16_t flags = 0;
  if (initiallyHigh) flags |= PORT_STUCK_HIGH;
  if (r.status == 2 || r.samples < MIN_USABLE_HEALTH_SAMPLES ||
      r.medianNs > MAX_SENSOR_SIGNATURE_NS)
    flags |= PORT_LEAK_OR_SHORT;
  // Cheap piezo/cable assemblies are usable with modest RC variation. Warn
  // only when variation exceeds both a 10 us floor and 10% of the median, or
  // when several of the 64 sweeps time out. One marginal sweep must not make
  // a channel alternate between Ready and Unstable on successive checks.
  if (r.samples && (r.samples < MIN_STABLE_HEALTH_SAMPLES ||
      (r.stddevNs > MAX_STABLE_STDDEV_NS &&
       r.medianNs && r.stddevNs > r.medianNs / 10UL))) flags |= PORT_UNSTABLE;
  if (crossCoupled) flags |= PORT_CROSS_COUPLED;
  if (r.samples && r.medianNs < MIN_SENSOR_SIGNATURE_NS) flags |= PORT_MISSING_SENSOR;
  return flags;
}

bool healthHasSeriousFault() {
  const uint16_t serious = PORT_STUCK_HIGH | PORT_LEAK_OR_SHORT |
      PORT_CROSS_COUPLED | PORT_MISSING_SENSOR;
  return ((health.channel1Flags | health.channel2Flags) & serious) != 0;
}

void notifyHealth() {
  chHealth.write((uint8_t*)&health, sizeof(health));
  chHealth.notify((uint8_t*)&health, sizeof(health));
}

bool performHealthCheck() {
  setState(ST_CHECKING);
  bool cross1 = false, cross2 = false, high1 = false, high2 = false;
  CalResult c1 = runCalibration(1, true, &cross1, &high1);
  CalResult c2 = runCalibration(2, true, &cross2, &high2);
  health.channel1Flags = classifyPort(c1, cross1, high1);
  health.channel2Flags = classifyPort(c2, cross2, high2);
  health.channel1SignatureNs = c1.medianNs;
  health.channel2SignatureNs = c2.medianNs;
  health.checkedAtBootMs = millis();
  notifyHealth();
  return !healthHasSeriousFault();
}

void notifyStatus() {
  uint16_t batteryMv = sampleBatteryMv();
  StatusPacket pkt = {
    state,
    pendingCount,
    (uint8_t)(timeValid ? 1 : 0),
    batteryPercentFromMv(batteryMv),
    batteryMv,
    0  // legacy battery-lock byte; voltage is warning-only in firmware 2.2+
  };
  chStatus.write((uint8_t*)&pkt, sizeof(pkt));
  chStatus.notify((uint8_t*)&pkt, sizeof(pkt));
}

void notifyPending(const Pending& p) {
  Result r = {};
  r.id      = p.id;
  r.splitNs = p.splitNs;
  r.epoch   = epochForBootMs(p.bootMs);   // flywheel: computed fresh each send
  r.flags   = p.flags | ((r.epoch != 0) ? RESULT_TIME_VALID : 0);
  r.startTicks = p.startTicks;
  r.stopTicks = p.stopTicks;
  r.batteryMv = p.batteryMv;
  r.portFlags = p.portFlags;
  r.bootId = bootId;
  r.resetCause = resetCause;
  r.hwRev = HW_REV;
  r.fwMajor = FW_MAJOR;
  r.fwMinor = FW_MINOR;
  r.formatVersion = 2;
  r.crc16 = crc16Ccitt((const uint8_t*)&r, sizeof(r) - sizeof(r.crc16));
  chResult.write((uint8_t*)&r, sizeof(r));
  chResult.notify((uint8_t*)&r, sizeof(r));
}

void notifyTrace(const Pending& p) {
  uint8_t chunkCount = (uint8_t)((p.traceCount + TRACE_EVENTS_PER_CHUNK - 1) /
                                 TRACE_EVENTS_PER_CHUNK);
  if (chunkCount == 0) chunkCount = 1;

  for (uint8_t chunk = 0; chunk < chunkCount; chunk++) {
    uint16_t eventStart = (uint16_t)chunk * TRACE_EVENTS_PER_CHUNK;
    uint8_t eventCount = 0;
    if (eventStart < p.traceCount) {
      uint16_t remaining = p.traceCount - eventStart;
      eventCount = (uint8_t)(remaining > TRACE_EVENTS_PER_CHUNK
          ? TRACE_EVENTS_PER_CHUNK : remaining);
    }

    uint8_t packet[180] = {};
    packet[0] = (uint8_t)(p.id & 0xFF);
    packet[1] = (uint8_t)(p.id >> 8);
    packet[2] = TRACE_FORMAT_VERSION;
    packet[3] = p.traceFlags;
    packet[4] = chunk;
    packet[5] = chunkCount;
    packet[6] = eventCount;
    packet[8] = (uint8_t)(p.traceCount & 0xFF);
    packet[9] = (uint8_t)(p.traceCount >> 8);
    packet[10] = (uint8_t)(eventStart & 0xFF);
    packet[11] = (uint8_t)(eventStart >> 8);
    packet[12] = (uint8_t)(p.traceBaseTicks & 0xFF);
    packet[13] = (uint8_t)((p.traceBaseTicks >> 8) & 0xFF);
    packet[14] = (uint8_t)((p.traceBaseTicks >> 16) & 0xFF);
    packet[15] = (uint8_t)((p.traceBaseTicks >> 24) & 0xFF);
    if (eventCount) {
      memcpy(&packet[16], &p.trace[eventStart], eventCount * sizeof(TraceEvent));
    }
    uint16_t payloadLen = (uint16_t)(16 + eventCount * sizeof(TraceEvent));
    uint16_t crc = crc16Ccitt(packet, payloadLen);
    packet[payloadLen] = (uint8_t)(crc & 0xFF);
    packet[payloadLen + 1] = (uint8_t)(crc >> 8);
    uint16_t packetLen = payloadLen + 2;
    chTrace.write(packet, packetLen);
    chTrace.notify(packet, packetLen);
    delay(15);
  }
}

void notifyTraceById(uint16_t id) {
  for (uint8_t i = 0; i < pendingCount; i++) {
    if (pending[i].id == id) {
      notifyTrace(pending[i]);
      return;
    }
  }
}

void storeResult(uint32_t splitNs, uint8_t flags, uint32_t startTicks, uint32_t stopTicks) {
  if (pendingCount >= MAX_PENDING) {           // buffer full: drop the oldest
    memmove(&pending[0], &pending[1], sizeof(pending[0]) * (MAX_PENDING - 1));
    pendingCount = MAX_PENDING - 1;
  }
  Pending& p = pending[pendingCount++];
  p.id      = nextId++;
  p.splitNs = splitNs;
  p.bootMs  = millis();                        // flywheel timestamp
  p.flags = flags;
  p.startTicks = startTicks;
  p.stopTicks = stopTicks;
  p.batteryMv = sampleBatteryMv();
  p.portFlags = (uint16_t)((health.channel1Flags & 0xFF) |
                           ((health.channel2Flags & 0xFF) << 8));
  p.traceBaseTicks = activeTraceBaseTicks;
  p.traceCount = activeTraceCount;
  p.traceFlags = activeTraceFlags;
  if (p.traceCount) {
    memcpy(p.trace, activeTrace, p.traceCount * sizeof(TraceEvent));
  }
  notifyPending(p);  // no-op if nobody is connected/subscribed; FETCH re-sends
}

void finishTimingFault(uint8_t faultFlag) {
  uint32_t startTicks = NRF_TIMER2->CC[0] == 0xFFFFFFFFUL ? 0 : NRF_TIMER2->CC[0];
  uint32_t stopTicks = NRF_TIMER2->CC[1] == 0xFFFFFFFFUL ? 0 : NRF_TIMER2->CC[1];
  armed = false;
  started = false;
  finished = false;
  disarmTiming();
  storeResult(0, activeResultFlags | faultFlag, startTicks, stopTicks);
  setState(ST_FAULT);
}

void finishCapturedPair() {
  uint32_t startTicks = NRF_TIMER2->CC[0];
  uint32_t stopTicks = NRF_TIMER2->CC[1];
  int32_t signedTicks = (int32_t)(stopTicks - startTicks);
  bool reversed = signedTicks < 0;
  uint32_t ticks = reversed
      ? (uint32_t)(-(int64_t)signedTicks)
      : (uint32_t)signedTicks;
  uint32_t splitNs = (uint32_t)(((uint64_t)ticks * 1000000000ULL) / TIMER_HZ);
  uint8_t flags = activeResultFlags;
  if (reversed) flags |= RESULT_STOP_BEFORE_START;
  if (splitNs < MIN_SPLIT_NS) flags |= RESULT_SPLIT_TOO_SHORT;
  if (splitNs > MAX_SPLIT_NS) flags |= RESULT_SPLIT_TOO_LONG;
  armed = false;
  started = false;
  finished = false;
  disarmTiming();
  storeResult(splitNs, flags, startTicks, stopTicks);
  setState((flags & (RESULT_STOP_BEFORE_START |
                     RESULT_SPLIT_TOO_SHORT |
                     RESULT_SPLIT_TOO_LONG)) ? ST_FAULT : ST_IDLE);
}

void beginArm(bool overrideFaults) {
  bool healthy = performHealthCheck();
  activeResultFlags = overrideFaults ? RESULT_ARM_OVERRIDE : 0;
  if (!healthy) activeResultFlags |= RESULT_PORT_WARNING;
  if (!healthy && !overrideFaults) { setState(ST_FAULT); return; }

  started = false;
  finished = false;
  armed = true;
  if (!armTiming()) {
    armed = false;
    activeResultFlags |= RESULT_CLOCK_FAULT;
    setState(ST_FAULT);
    return;
  }
  setState(ST_ARMED);
}

void ackResult(uint16_t id) {
  for (uint8_t i = 0; i < pendingCount; i++) {
    if (pending[i].id == id) {
      memmove(&pending[i], &pending[i + 1], sizeof(pending[0]) * (pendingCount - i - 1));
      pendingCount--;
      break;
    }
  }
}

void setState(uint8_t s) {
  state = s;
  notifyStatus();
}

void waitForButtonRelease() {
  uint32_t releasedAtMs = 0;
  while (true) {
    if (digitalRead(WAKE_BUTTON_PIN) == HIGH) {
      if (releasedAtMs == 0) releasedAtMs = millis();
      if ((uint32_t)(millis() - releasedAtMs) >= BUTTON_DEBOUNCE_MS) return;
    } else {
      releasedAtMs = 0;
    }
    delay(1);
  }
}

void enterSystemOff() {
  statusLedWrite(false);
  nrf_gpio_cfg_default(g_ADigitalPinMap[SENSOR1_PIN]);
  nrf_gpio_cfg_default(g_ADigitalPinMap[SENSOR2_PIN]);
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE1_PIN]);
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE2_PIN]);
  systemOff(WAKE_BUTTON_PIN, LOW);  // D4 pull-up, wake when the button pulls low
  while (true) delay(1000);         // systemOff() does not normally return
}

void showPowerOnPattern() {
  for (uint8_t i = 0; i < 3; i++) {
    statusLedWrite(true);
    delay(90);
    statusLedWrite(false);
    delay(90);
  }
}

void showPowerOffPattern() {
  for (uint8_t i = 0; i < 2; i++) {
    statusLedWrite(true);
    delay(240);
    statusLedWrite(false);
    delay(180);
  }
}

void requireLongPressAfterSystemOffWake(uint32_t startupResetCause) {
  if ((startupResetCause & POWER_RESETREAS_OFF_Msk) == 0) {
    showPowerOnPattern();
    return;
  }

  delay(BUTTON_DEBOUNCE_MS);
  if (digitalRead(WAKE_BUTTON_PIN) != LOW) enterSystemOff();

  uint32_t pressedAtMs = millis();
  while ((uint32_t)(millis() - pressedAtMs) < POWER_LONG_PRESS_MS) {
    if (digitalRead(WAKE_BUTTON_PIN) != LOW) {
      waitForButtonRelease();
      enterSystemOff();
    }
    statusLedWrite(((millis() - pressedAtMs) / 180UL & 1UL) == 0UL);
    delay(2);
  }

  statusLedWrite(true);             // hold accepted
  waitForButtonRelease();
  showPowerOnPattern();
}

void powerOffFromButton() {
  healthRequested = 0;
  calRequested = 0;
  fetchRequested = false;
  armed = false;
  started = false;
  finished = false;
  disarmTiming();
  state = ST_IDLE;
  showPowerOffPattern();
  waitForButtonRelease();
  enterSystemOff();
}

void pollUserButton() {
  static bool rawDown = false;
  static bool stableDown = false;
  static uint32_t rawChangedAtMs = 0;

  bool down = digitalRead(WAKE_BUTTON_PIN) == LOW;
  if (down != rawDown) {
    rawDown = down;
    rawChangedAtMs = millis();
  }
  if (down == stableDown) {
    if (stableDown &&
        (uint32_t)(millis() - powerButtonPressedAtMs) >= POWER_LONG_PRESS_MS) {
      powerOffFromButton();
    }
    return;
  }
  if ((uint32_t)(millis() - rawChangedAtMs) < BUTTON_DEBOUNCE_MS) return;

  stableDown = down;
  powerButtonHolding = stableDown;
  if (stableDown) powerButtonPressedAtMs = millis();
}

// --------------------------------------------------------- BLE callbacks
void onControlWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl; (void)chr;
  if (len < 1) return;
  uint16_t arg = (len >= 3) ? (uint16_t)(data[1] | (data[2] << 8)) : 0;

  switch (data[0]) {
    case CMD_VERIFY1: armed = false; disarmTiming(); setState(ST_VERIFY1); break;
    case CMD_VERIFY2: armed = false; disarmTiming(); setState(ST_VERIFY2); break;
    case CMD_ARM:
      if (state != ST_ARMED && state != ST_RUNNING) healthRequested = 1;
      break;
    case CMD_ARM_OVERRIDE:
      if (state != ST_ARMED && state != ST_RUNNING) healthRequested = 2;
      break;
    case CMD_DISARM:
    case CMD_CANCEL:
      healthRequested = 0;
      calRequested = 0;
      armed    = false;
      started  = false;
      finished = false;
      disarmTiming();
      setState(ST_IDLE);
      break;
    case CMD_ACK: {
      // Defer the buffer edit to loop() — never mutate pending[] here.
      uint8_t nt = (uint8_t)((ackTail + 1) % MAX_PENDING);
      if (nt != ackHead) { ackQueue[ackTail] = arg; ackTail = nt; }
      break;
    }
    case CMD_FETCH:
      fetchRequested = true;   // handled in loop() — keeps this callback quick
      break;
    case CMD_FETCH_TRACE:
      traceFetchRequested = arg;
      break;
    case CMD_CALIBRATE:
      // Deferred to loop() like FETCH; refused while a shot could arrive.
      if (state != ST_ARMED && state != ST_RUNNING && arg >= 1 && arg <= 2) {
        calRequested = (uint8_t)arg;
      }
      break;
    case CMD_HEALTH:
      if (state != ST_ARMED && state != ST_RUNNING) healthRequested = 3;
      break;
    case CMD_IDENTIFY:
      identifyUntilMs = millis() + 5000UL;
      break;
  }
}

void onTimeWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl; (void)chr;
  if (len < 4) return;
  epochBase  = (uint32_t)data[0] | ((uint32_t)data[1] << 8) |
               ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
  millisBase = millis();
  timeValid  = (epochBase != 0);
  notifyStatus();
}

// When the app subscribes to STATUS, push the current state right away.
void onStatusCccd(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t value) {
  (void)conn_hdl; (void)chr;
  if (value & 0x0001) notifyStatus();
}

void onConnect(uint16_t conn_hdl) {
  (void)conn_hdl;
}

void onDisconnect(uint16_t conn_hdl, uint8_t reason) {
  (void)conn_hdl; (void)reason;
  // Deliberately do nothing: if we're ARMED or RUNNING we stay that way,
  // the hardware keeps capturing, the result is stored, and advertising
  // restarts automatically so the phone can reconnect and collect it.
}

// ------------------------------------------------------------------ setup
void setup() {
  statusLedBegin();
  pinMode(WAKE_BUTTON_PIN, INPUT_PULLUP);
  uint32_t startupResetCause = NRF_POWER->RESETREAS;
  requireLongPressAfterSystemOffWake(startupResetCause);
  bootId = makeBootId();
  resetCause = startupResetCause;
  NRF_POWER->RESETREAS = resetCause;

  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  char advertisedName[12];
  snprintf(advertisedName, sizeof(advertisedName), "Chrono-%04X",
           (unsigned)(NRF_FICR->DEVICEID[0] & 0xFFFFUL));
  Bluefruit.setName(advertisedName);
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);

  // Set up the capture hardware after Bluefruit.begin() so the SoftDevice
  // is running when we touch PPI/HFXO.
  setupTiming();

  svc.begin();

  chStatus.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chStatus.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chStatus.setFixedLen(sizeof(StatusPacket));
  chStatus.setCccdWriteCallback(onStatusCccd);
  chStatus.begin();

  chControl.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  chControl.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chControl.setMaxLen(3);
  chControl.setWriteCallback(onControlWrite);
  chControl.begin();

  chResult.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chResult.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chResult.setFixedLen(sizeof(Result));
  chResult.begin();

  chTime.setProperties(CHR_PROPS_WRITE);
  chTime.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chTime.setMaxLen(4);
  chTime.setWriteCallback(onTimeWrite);
  chTime.begin();

  chCal.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chCal.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chCal.setFixedLen(sizeof(CalResult));
  chCal.begin();

  chInfo.setProperties(CHR_PROPS_READ);
  chInfo.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chInfo.setFixedLen(sizeof(HwInfo));
  chInfo.begin();
  // Direct protected-GPIO board: 16 MHz capture, HFXO +/-30 ppm, and a
  // conservative 300 ns per-edge threshold-walk allowance.
  HwInfo info = {
    HW_REV, FW_MAJOR, FW_MINOR, 0, 62500UL, 30, 300,
    NRF_FICR->DEVICEID[0], NRF_FICR->DEVICEID[1], 2, 1, 0x000F
  };
  chInfo.write((uint8_t*)&info, sizeof(info));

  chHealth.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chHealth.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chHealth.setFixedLen(sizeof(HealthPacket));
  chHealth.begin();
  chHealth.write((uint8_t*)&health, sizeof(health));

  chTrace.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chTrace.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chTrace.setMaxLen(180);
  chTrace.begin();

  notifyStatus();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(svc);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);   // key to auto-reconnect
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);                    // advertise forever
}

// ------------------------------------------------------------------- loop
void loop() {
  pollUserButton();

  switch (state) {
    case ST_VERIFY1:
      if (digitalRead(SENSOR1_PIN) == HIGH) setState(ST_VERIFY1_OK);
      break;

    case ST_VERIFY2:
      if (digitalRead(SENSOR2_PIN) == HIGH) setState(ST_VERIFY2_OK);
      break;

    case ST_ARMED:
    case ST_RUNNING:
      // GPIOTE_IRQHandler clears event registers after copying trace samples,
      // so the preserved first-edge capture registers are the source of truth.
      {
        bool haveStart = NRF_TIMER2->CC[0] != 0xFFFFFFFFUL;
        bool haveStop = NRF_TIMER2->CC[1] != 0xFFFFFFFFUL;
        if (!started && (haveStart || haveStop)) {
          started = true;
          startedAtMs = millis();
          setState(ST_RUNNING);
        }
        if (started && !finished && haveStart && haveStop) {
          finished = true;
        }

        // Keep both inputs live after the automatic pair so later impact and
        // ringing transitions are available for user-selected cursors.
        if (started && finished &&
            (uint32_t)(millis() - startedAtMs) >= TRACE_CAPTURE_MS) {
          finishCapturedPair();
        } else if (started && !finished &&
                   (uint32_t)(millis() - startedAtMs) >= STOP_TIMEOUT_MS) {
          finishTimingFault(haveStop ? RESULT_STOP_BEFORE_START : RESULT_STOP_TIMEOUT);
        }
      }
      break;

    default:
      break;
  }

  // Apply any ACKs the phone sent (buffer is only mutated here).
  bool acked = false;
  while (ackHead != ackTail) {
    uint16_t id = ackQueue[ackHead];
    ackHead = (uint8_t)((ackHead + 1) % MAX_PENDING);
    ackResult(id);
    acked = true;
  }
  if (acked) notifyStatus();

  if (calRequested) {
    uint8_t ch = calRequested;
    calRequested = 0;
    uint8_t prev = state;
    setState(ST_CALIBRATING);
    runCalibration(ch, true, nullptr, nullptr);
    setState(prev);   // resume idle or the verify-ok state the wizard shows
  }

  if (healthRequested) {
    uint8_t request = healthRequested;
    healthRequested = 0;
    if (request == 3) {
      performHealthCheck();
      setState(healthHasSeriousFault() ? ST_FAULT : ST_IDLE);
    } else {
      beginArm(request == 2);
    }
  }

  if (fetchRequested) {
    fetchRequested = false;
    for (uint8_t i = 0; i < pendingCount; i++) {
      notifyPending(pending[i]);
      delay(15);   // give the BLE stack room between notifications
    }
  }

  if (traceFetchRequested) {
    uint16_t id = traceFetchRequested;
    traceFetchRequested = 0;
    notifyTraceById(id);
  }

  if ((uint32_t)(millis() - lastBatteryNotifyMs) >= 30000UL) {
    lastBatteryNotifyMs = millis();
    notifyStatus();
  }

  // Priority: power hold, Identify, active timing, fault, checking, heartbeat.
  if (powerButtonHolding) {
    statusLedWrite((((millis() - powerButtonPressedAtMs) / 180UL) & 1UL) == 0UL);
  } else if ((int32_t)(identifyUntilMs - millis()) > 0) {
    statusLedWrite(((millis() / 150UL) & 1UL) == 0UL);
  } else if (state == ST_ARMED || state == ST_RUNNING) {
    statusLedWrite(((millis() / 120UL) & 1UL) == 0UL);
  } else if (state == ST_FAULT) {
    uint32_t phase = millis() % 1200UL;
    statusLedWrite(phase < 120UL || (phase >= 240UL && phase < 360UL));
  } else if (state == ST_CHECKING) {
    statusLedWrite(true);
  } else {
    statusLedWrite((millis() % 2000UL) < 60UL);
  }

  delay(2);
}
