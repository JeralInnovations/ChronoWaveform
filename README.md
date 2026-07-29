# Chrono Waveform — Reviewable BLE Chronograph

A two-sensor chronograph with dedicated builds for the **nice!nano v2** and
the economical **Seeed Studio XIAO nRF52840 PTH board**, controlled by a native
**Android app** over Bluetooth Low Energy.

This waveform edition runs on the existing Chrono hardware and records each
observable high/low transition on both sensor inputs. The Android app renders
the aligned traces and lets the user place reviewed START and STOP cursors
instead of having to accept the first edge-to-edge result.

- Sensor 1 (START) pulls input **D0** high → the clock starts
- Sensor 2 (STOP) pulls input **D1** high → the clock stops
- The split time is captured by hardware timer logic with 62.5 ns tick resolution
- Results survive BLE disconnects: the device stores up to 16 un-collected results
  and the app reconnects automatically and downloads them
- The automatic first-edge result is preserved alongside any reviewed time
- The app fits both traces to the screen, supports zoom/pan and snap-to-edge
  cursors, and exports the transition list

```
ChronoWaveform/
├── firmware/ChronographNiceNano/           ← flash this to a nice!nano v2
├── firmware/ChronographXiao/               ← flash this to the XIAO PTH board
├── firmware/Chronograph/                   ← canonical/reference firmware source
├── android/                               ← open this folder in Android Studio
├── hardware/                              ← PCB design, BOM, protection front-end
└── README.md
```

## Choose the correct build

| Hardware | Sketch | Power and battery behavior | LED |
|---|---|---|---|
| Original nice!nano v2 logger | `firmware/ChronographNiceNano/ChronographNiceNano.ino` | 1S LiPo on the board battery/input rail. Firmware reads VDDH with `analogReadVDDHDIV5()` and applies the 3.40 V / 3.50 V arm-lock hysteresis. | GPIO 15 |
| Economical two-channel PTH logger | `firmware/ChronographXiao/ChronographXiao.ino` | Protected 1S LiPo on the XIAO `BAT+`/`BAT-` pads. Firmware reads the onboard divider and applies the 3.40 V / 3.50 V arm-lock hysteresis. Never feed `3V3`. | D5 through 330 ohms |

Both builds use D0/D1 for timing, D2/D3 for RC diagnostics, and D4 for the
button. The XIAO board reserves D6/D7 for UART and D8/D9 for remappable I2C.

Additional guides:

- `docs/WAVEFORM_REVIEW_EDITION.md` defines trace capture, BLE transport,
  waveform review, limitations, and validation for this existing-hardware edition.
- `docs/PORT_FRONT_END_MATH.md` explains the current GPIO piezo front-end,
  calibration signature math, expected timing variability, and GAE assumptions.
- `docs/CHEAP_BRASS_PIEZO_GUIDE.md` explains how to sort, pair, and validate
  inexpensive brass piezo sensors for the entry and advanced builds.
- `docs/NRF52840_BOARD_VARIANTS.md` captures the current two-board PCB layout
  target: a two-channel unbuffered nRF52840 board and a four-channel buffered
  nRF52840 board.
- `docs/PIEZO_INPUT_PROTECTION.md` records the recommended two-stage piezo
  input protection path and validation plan.
- `docs/FOUR_CHANNEL_WATERPROOF_BUILD.md` lays out the planned four-channel
  waterproof XIAO nRF52840 entry build with battery and pogo charging.
- [hardware/README.md](hardware/README.md) — the economical two-channel board
  design: per-port piezo protection (TVS + Schottky clamps + bleed), BOM with
  part numbers, net-class/DRC files, and layout guidance.
- `docs/ECONOMICAL_TWO_CHANNEL_PTH.md` is the authoritative schematic, pin,
  diagnostics, and feature profile for the first through-hole board.
- `docs/NICE_NANO_TWO_CHANNEL.md` records the original LiPo-powered nice!nano
  profile and its pin-level differences.

---

## 1. Hardware & wiring

Each input normally sits at 0 V because the chip enables an internal pull-down.
For a quick electrical test, a switch may momentarily connect the input to
**3.3 V**. The actual piezo circuit below creates the positive pulse itself.

Each channel has a piezo front-end and a calibration charge path. The important
part is the **sensor node**: it is one physical junction (for example, one PCB
pad or soldered splice), not a component. Five connections meet there.

```text
piezo + / connector A --+-- 1k resistor --+-- MCU timing input (D0 for CH1, D1 for CH2)
                        |                  |
                        +-- P4KE15CA ----- GND
                        +-- 470k --------- GND
                                        |
                                        +-- upper Schottky: anode here, cathode to 3V3
                                        |
                                        +-- lower Schottky: cathode here, anode to GND
                                        |
charge/test pin -- 10k 1% resistor -----+
  (D2 for CH1, D3 for CH2)

piezo - / connector B ---------------------- GND
```

### Build each junction

Make the following two identical junctions. Do **not** join CH1 and CH2
together; each channel has its own sensor node.

| Channel | Make this one sensor-node junction by joining... |
|---|---|
| CH1 / START | the far end of CH1's 1 kOhm resistor; XIAO **D0**; the **anode** of the upper 1N5711; the **cathode** of the lower 1N5711; and the far end of CH1's 10 kOhm resistor. |
| CH2 / STOP | the far end of CH2's 1 kOhm resistor; XIAO **D1**; the **anode** of the upper 1N5711; the **cathode** of the lower 1N5711; and the far end of CH2's 10 kOhm resistor. |

Then make these connections away from the junction:

| Channel | Connection |
|---|---|
| CH1 / START | Piezo `+` (connector A) -> other end of CH1's 1 kOhm resistor. Piezo `-` (connector B) -> **GND**. Other end of CH1's 10 kOhm resistor -> **D2**. Upper-Schottky **cathode** -> **3V3**. Lower-Schottky **anode** -> **GND**. |
| CH2 / STOP | Piezo `+` (connector A) -> other end of CH2's 1 kOhm resistor. Piezo `-` (connector B) -> **GND**. Other end of CH2's 10 kOhm resistor -> **D3**. Upper-Schottky **cathode** -> **3V3**. Lower-Schottky **anode** -> **GND**. |

On a typical discrete diode, the painted stripe marks the **cathode**. Therefore:

- Upper clamp: stripe to **3V3**; unstriped end to the sensor node.
- Lower clamp: stripe to the sensor node; unstriped end to **GND**.

Per channel:

| From | Component | To |
|---|---|---|
| Piezo + | 1 kΩ series resistor | sensor node |
| Sensor node | — | **D0** (ch 1) / **D1** (ch 2) |
| Sensor node | Schottky, anode at node | **3V3** (positive clamp; cathode at 3V3) |
| Sensor node | Schottky, cathode at node | **GND** (negative clamp; anode at GND) |
| **D2** (ch 1) / **D3** (ch 2) | 10 kΩ **1% metal film** | sensor node |
| Piezo – | wire | **GND** |

The 10 k charge resistors are only used for calibration; those pins are held in
true hi-Z during live fire so they don't load the node. Match the two channels —
same piezo type, same clamp diodes, and **the same cable length** (~5 ns/m).

> ⚠️ Use **3V3**, not 5V. The nRF52840 pins are 3.3 V only.

---

### Support connections

| Function | Connection |
|---|---|
| Wake/user button | XIAO D4 to momentary switch to GND |
| Status LED | XIAO D5 through 330 ohms to LED anode; LED cathode to GND |
| UART reserved | D6 TX and D7 RX |
| Remappable I2C | D8/D9 |

## 2. Firmware - flashing either logger

### XIAO nRF52840 PTH board

1. **Install the Arduino IDE** (2.x) from https://www.arduino.cc/en/software
2. **Add the Seeed nRF52 board package:**
   - *File → Preferences → Additional boards manager URLs*, paste:
     ```
     https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
     ```
   - *Tools -> Board -> Boards Manager*, search **"seeed nrf52"** and install
     the non-mbed **Seeed nRF52 Boards** package. The firmware uses Bluefruit
     and direct nRF52840 peripherals, so do not select the mbed-enabled core.

3. **Open the sketch:** `firmware/ChronographXiao/ChronographXiao.ino`
4. **Select the board:** *Tools -> Board -> Seeed nRF52 Boards -> Seeed XIAO nRF52840*.

5. **Plug in the XIAO** over USB-C and select its port under *Tools -> Port*.
6. Click **Upload** (→ arrow button).

**If the upload fails or no port appears:** double-tap the tiny RESET button next
to the USB connector quickly (like a mouse double-click). The onboard LED breathes
and a new port appears — that's the bootloader. Select that port and upload again.

When running, the device advertises as **`Chrono-XXXX`**, where the suffix is
derived from its permanent MCU identity. The external D5 LED indicates state
and flashes rapidly for the app's Identify command.

### nice!nano v2 logger

1. Add this board-manager URL in Arduino IDE preferences:
   ```
   https://raw.githubusercontent.com/JeralInnovations/nicenano-v2-arduino/master/package_nicenano_index.json
   ```
2. Install **nice!nano nRF52** from Boards Manager.
3. Open `firmware/ChronographNiceNano/ChronographNiceNano.ino`.
4. Select *nice!nano nRF52 -> nice!nano v2* and upload normally.

This build expects a protected 1S LiPo on the nice!nano battery/input rail. Its
board-specific firmware reads that rail and blocks a new arm at or below 3.40 V;
the lock releases only after a filtered reading of at least 3.50 V.

---

## 3. Android app — installing on your phone

### Option A — install straight from your phone (easiest)

Every push to this GitHub repo automatically builds the app (see the *Actions*
tab). To install it, on your phone's browser:

1. Open the repo's **Releases** page:
   `https://github.com/JeralInnovations/ChronoWaveform/releases`
2. Under **Latest app build**, download `app-debug.apk`.
3. Open the downloaded file. Android will warn about installing unknown apps —
   allow it for your browser when prompted, then tap *Install*.

> If a future update refuses to install over the old version ("App not
> installed"), uninstall the old one first. Automated builds are signed with a
> throwaway debug key that can change between builds.

### Option B — build it yourself with Android Studio

You don't need any Android experience; Android Studio does everything.

1. **Install Android Studio** (free) from https://developer.android.com/studio
   — accept the defaults during setup so it installs the Android SDK.
2. *File → Open…* and select the **`ChronoWaveform/android`** folder (the folder itself,
   not a file inside it). Click *Trust Project* if asked.
3. Wait for the first **Gradle sync** to finish (bottom status bar — the first one
   downloads a few hundred MB of build tools; later syncs are fast). If prompted to
   accept SDK licenses or install missing SDK components, click accept/install.
4. **Enable USB debugging on your phone:**
   - *Settings → About phone* → tap **Build number** seven times ("You are now a developer!")
   - *Settings → System → Developer options* → turn on **USB debugging**
5. Plug the phone into the computer with USB. On the phone, allow the
   "USB debugging?" prompt.
6. Your phone appears in the device dropdown at the top of Android Studio.
   Press the green **Run ▶** button. The app builds, installs, and launches.

To share the app without a cable: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*,
then copy `android/app/build/outputs/apk/debug/app-debug.apk` to any phone and open
it (allow "install from unknown sources" when asked).

> Command-line note: the project ships without the Gradle wrapper JAR. Android
> Studio doesn't need it, but if you want `gradlew` on the command line, run
> `gradle wrapper` once in `android/` (requires a local Gradle install).

---

## 4. Using it

1. **Connect** — open the app, grant the Bluetooth permission, power the device.
   It appears in the list; tap it.
2. **Port baseline** — with both ports EMPTY, the app measures each bare port's
   electrical signature (an RC sweep through the 10 k calibration resistor).
   Keep the device still during the sweep.
3. **Sensor 1 (START)** — insert the START sensor leads into port 1, then
   trigger the sensor once. The graphic glows green, and the app automatically
   re-measures the channel with the sensor attached — the difference against
   the baseline isolates the cable+sensor load. If the reading looks like an
   empty port, the app warns you the sensor may not be plugged in.
4. **Sensor 2 (STOP)** — same procedure for port 2.
5. **Sensor spacing** — enter the measured distance between the sensors
   (inches by default; mm / cm / ft also available). Velocity is computed from
   this, so measure carefully. At a 6 in gap, 0.02 in of error is 1%.

The dashboard's **Channel calibration** card shows each port's measured load
(≈ pF) and flags whether the two channels are matched; **Recheck** reruns the
loaded sweep any time (sensors attached, undisturbed). Every sweep is appended
to `cal_history.jsonl` in the app's private storage for later analysis.

**Project folders, test subfolders, photos.** Field data lives in the phone's
public storage under **`Documents/ChronoData/<project>/<test>/`** — browsable
in any file manager. The **project** folder is per day, named by date (you can
rename it when it's created); a **new day** prompts you to start a new project
or keep logging into the previous one — it does *not* prompt after every shot.
Each **test** is a subfolder named by that shot's label (or `Test1`, `Test2`…
auto-incrementing when no label is given), holding `shot.json` plus the shot's
photos. Tap the folder path at the top of the dashboard (or the *Files* button
above the results) to open it. After setup the app prompts for **setup photos**
of the rig; after each shot you first get a **results screen**, then the
**after photos** prompt. Opening a past shot in the editor shows a row of photo
thumbnails — tap one for a full-screen view, tap again to close. **Log manual entry** (dashboard, or "Manual
logging" on the connect screen — a simplified, device-free view) records a
shot with no chronograph: typed velocity (ft/s or m/s, optional), all detail
blanks, date/time, and the same photo prompts. Tapping an empty date box
autofills the current date and pre-selects the time for quick editing.

**Replacing a consumed sensor** is a guided three-step flow (tap the torn
sensor): fit the wire with nothing armed, press *Sensor attached* to run a
capacitance check against the bare-port baseline (catches a sensor that
isn't actually connected), and only then arm the tap test — so movement
during placement can't cause a trigger.

**Exporting data.** The *Export* button above the results list shares a CSV of
every result (label, date, split, distance, velocities) plus the raw
calibration history (`.jsonl`) through Android's share sheet — email it,
save to Drive, etc.

**Hardware identification & confidence.** The device reports its hardware
revision and timing spec (timer tick, crystal tolerance, front-end jitter)
over BLE, and each result shows a ~95% confidence interval computed from
those numbers plus your rig's measured channel mismatch and a 0.5 mm
gate-spacing assumption. A future hardware revision that reports tighter
numbers (e.g. a TDC front end) automatically shows tighter confidence — no
app update needed. Unidentified (older) firmware is assumed to be rev 1.

**Break-screens are consumable.** Every recorded shot automatically marks both
sensors as consumed on the dashboard's rig diagram (torn amber screens) and
disables ARM. Fit fresh wire, tap the torn sensor in the diagram to retest it —
the app re-verifies the trigger and automatically re-measures the new screen's
electrical load before the next shot can be armed.
5. **Dashboard** — from here you can:
   - **Retest 1 / Retest 2** — re-run either sensor test any time
   - **Change** — edit the sensor spacing
   - **Test label** — optional name applied to the next result (editable later too)
   - **ARM — TEST STANDBY** — the device waits for sensor 1, times the split to
     sensor 2, and reports the result
6. **Walk away if you need to.** If the phone goes out of range while armed, the
   device keeps waiting, times the shot, and stores the result (up to 16). The app
   shows *Reconnecting…* and automatically pulls the results in as soon as you're
   back in range.
7. **Time & date** — the app syncs the phone's clock to the device on every
   connection (and via the clock chip at the top of the dashboard). The device
   runs a **flywheel clock from boot**: every shot is stamped against it, so a
   sync at *any* point during that boot — before or after the shot — applies
   the correct wall-clock time retroactively or forward. Only shots from a
   boot that never got a single sync show a grayed *"No date"* — tap the
   pencil to type one in (`yyyy-MM-dd HH:mm`).
8. **Test details** — the *Next test* card has blanks for **Label**, **Tool**,
   **Target**, and **Distance to target**, applied to the next shot (tool and
   target persist between sessions). The **Result** blank is filled in via the
   pencil on the result card after you've seen the target. Everything exports
   to CSV.

Each result shows **ft/s**, **m/s**, and the raw split. The device reports
nanoseconds from 62.5 ns hardware timer ticks. Results are stored on the phone
and survive app restarts.

---

## 4a. Simulation mode (no hardware needed)

Simulation never requires Bluetooth or its permission: if the permission
screen appears, tap **"Continue in simulation mode"**; otherwise use the
**"Simulation mode — no hardware needed"** button at the bottom of the connect
screen. Either way you can walk the entire UI with no chronograph present — handy for demos or trying the
app before the hardware is built. A fake device drives the same screens the real
one does. The dashboard can select stuck-high, leakage/short, unstable, coupled,
missing-sensor, STOP-before-START, STOP-timeout, and impossible-split scenarios.
Time sync and simulated signal loss are also supported. A small **SIM** badge
marks the session. Tap *Disconnect* to leave.

## 5. Design notes & limits

- **Timing accuracy** — the split is captured entirely in hardware
  (GPIOTE → PPI → TIMER2 at 16 MHz), so the timestamp is frozen the instant the
  edge arrives, independent of the CPU or the BLE stack. Resolution is 62.5 ns and
  the split is reported in nanoseconds. The crystal (HFXO) is held on while armed so
  the timer runs accurately; a PPI fork disables each channel after its first edge
  so piezo ringing can't overwrite a timestamp. At a 250 µs split (≈3000 fps over
  9 in) the timer contributes well under 0.05% error — your sensor matching and
  gate-spacing measurement dominate, not the electronics.
- **One-shot arming** — after each shot the device disarms itself; tap ARM again
  for the next test. Results are only deleted from the device after the app
  confirms it has stored them.
- **Keep the app open during standby.** Android pauses Bluetooth for backgrounded
  apps after a while; the reconnect logic is most reliable with the app in the
  foreground (screen can be off briefly).
- **Device clock** — neither board has a battery-backed clock. Time is re-synced on
  every connection and is lost on power-off, which is exactly why untimed results
  get the grayed-out manual date field.
- **False triggers** — the START input is latched once while armed; noise on the
  wires can still trigger it, so keep sensor leads short or twisted.
- **Power-loss boundary** — results are retained through BLE loss and are deleted
  only after the app stores and acknowledges them. The current queue is still in
  RAM, so complete logger power loss before collection can lose pending results.
- **Battery lockout** — both builds use a protected 1S LiPo and refuse a new
  arm at or below 3.40 V, releasing it after a filtered reading reaches 3.50 V.
  The nice!nano reads VDDH with `analogReadVDDHDIV5()`; the XIAO reads its
  onboard BAT divider with `PIN_VBAT` and `PIN_VBAT_ENABLE`. An in-progress
  shot is never interrupted.

## 6. BLE protocol (for reference / hacking)

Service `a5c40001-9d95-4e4c-8c5a-c1d6f2a80de1`

| Characteristic | UUID (…-9d95-4e4c-8c5a-c1d6f2a80de1) | Access | Payload |
|---|---|---|---|
| Status  | `a5c40002` | read/notify | `state, pendingCount, timeValid, batteryPercent, batteryMv, batteryArmLocked` |
| Control | `a5c40003` | write | `[cmd, argLo, argHi]` |
| Result  | `a5c40004` | read/notify | v1 prefix plus raw ticks, battery, port flags, boot/reset IDs, revisions, format, CRC |
| Time    | `a5c40005` | write | unix seconds `u32` (LE) |
| Cal     | `a5c40006` | read/notify | `ch:u8, status:u8, n:u16, median:u32, mean:u32, stddev:u32, min:u32` ns (LE) |
| Info    | `a5c40007` | read | revisions, timing model, MCU serial, channels, input stage, capabilities |
| Health  | `a5c40008` | read/notify | version, channels, per-port flags/signatures, boot-relative check time |
| Trace   | `a5c40009` | read/notify | chunked 24-bit edge offsets, channel/level metadata, trace flags, and CRC |

States: 0 idle · 1/3 verifying sensor 1/2 · 2/4 sensor 1/2 OK · 5 armed ·
6 running · 7 calibrating · 8 checking ports · 9 fault/refused.
Commands: 1/2 verify sensor 1/2 · 3 arm · 4 disarm · 5 ack result (arg=id) ·
6 cancel · 7 re-send stored results · 8 calibrate (arg=channel) · 9 port health ·
10 identify · 11 logged arm override · 12 fetch retained trace (arg=result ID).
