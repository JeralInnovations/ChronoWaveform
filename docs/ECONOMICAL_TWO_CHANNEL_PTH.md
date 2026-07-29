# Economical Two-Channel PTH Board

This is the authoritative profile for the first inexpensive through-hole
Chrono Logger PCB. It supersedes the earlier low-capacitance TVS, BAT54S, and
2.2k prototype recommendations for this specific board.

## Board Profile

| Function | Part or pin |
|---|---|
| MCU | Seeed Studio XIAO nRF52840 |
| Power input | Protected 1S LiPo to XIAO `BAT+`/`BAT-` pads; never power the board through `3V3` |
| START timing input | D0 |
| STOP timing input | D1 |
| START charge/test | D2 through 10k 1% |
| STOP charge/test | D3 through 10k 1% |
| Power button | D4 to GND; firmware pull-up; hold 1.5 seconds for System OFF or wake; short presses do nothing |
| External status LED | D5 through 330 ohms |
| Reserved UART | D6/D7 |
| Available/remappable I2C | D8/D9 |
| Hardware revision | 2 |
| Input stage | Direct protected GPIO, two channels |

The external LED is assumed active-high: `D5 -> 330 ohm -> LED anode`, with
the LED cathode connected to GND. A short pulse every 2 seconds is the powered
heartbeat. A rapid repeating blink means the logger is armed or actively
timing; solid means a port check is running, and a double blink indicates a
fault.

## Final PTH Input Network

Build the following circuit twice, once for START and once for STOP:

```text
piezo + / connector A ---- raw junction ---- 1k 1% ---- sense junction ---- D0 or D1
                              |                              |
                              +-- P4KE15CA -- GND           +-- 1N5711 to 3V3
                              |                              |   anode at sense
                              +-- 470k ------ GND           |   cathode at 3V3
                                                             |
charge D2 or D3 -------------------- 10k 1% ------------------+
                                                             |
GND ------------------------------ 1N5711 --------------------+
                                    anode at GND; cathode at sense

piezo - / connector B --------------------------------------- GND
```

The `P4KE15CA` is a bidirectional connector-side surge clamp. The two `1N5711`
diodes are the final steering clamps at the MCU sense node. The `470k` resistor
bleeds residual piezo charge without materially loading a normal pulse. The
`1k` resistor limits the current that reaches the rail clamps and MCU node.

Keep the TVS return short and wide, keep both channels physically matched, and
place a local 3V3 decoupling capacitor near the upper steering clamps.

## Diagnostics This Hardware Supports

The 10k charge paths let firmware perform an RC threshold sweep on each port.
The economical board can conservatively detect:

- an input already stuck high;
- a charge timeout consistent with a short or heavy conductive leakage;
- an unstable RC signature;
- a likely cross-channel connection;
- a missing or electrically insignificant sensor load.

These are electrical observations. In particular, firmware reports
`conductive leakage or short suspected`; it cannot prove that moisture caused
the leakage.

Firmware 2.3 treats at least 48 of 64 valid sweeps as electrically usable. It
reports a non-blocking variable-signature warning when fewer than 60 sweeps
complete or when standard deviation exceeds both 10 us and 10% of the median.
This avoids Ready/Unstable flicker from one marginal sweep while preserving a
warning for genuinely noisy or intermittent wiring.

The XIAO's onboard 1S-LiPo path supplies both charging and pack-voltage
measurement. Firmware holds `PIN_VBAT_ENABLE` (D14 / P0.14) low, then reads
`PIN_VBAT` (D32 / P0.31) through the board's approximately 2.961:1 divider.
It filters the measurement for display, warnings, and result records. Low
voltage never blocks arming. Do not apply an AA pack to `3V3`; that pin is the XIAO
regulator output. The 3V3 output still supplies the upper piezo steering clamps
exactly as shown in the input network.

## Firmware Safety Behavior

Before every normal arm, firmware checks both ports and refuses to arm if a
serious fault is present. A deliberately selected override is marked in the
result flags.

START and STOP first-edge captures are armed together. Each channel latches its
own hardware timestamp and disables itself against piezo ringing. If STOP is
captured first, firmware retains the measured magnitude, sets the
STOP-before-START flag, and the app displays a negative split and velocity with
an asterisk. A missing second edge still times out, and out-of-range splits
remain flagged.

The high-frequency crystal must report running before the logger enters the
armed state. Results include raw START/STOP timer captures, fault flags, logger
and boot identity, reset cause, revisions, packet format, and CRC.

Battery voltage is filtered in firmware and shown as a warning when low. The
logger still accepts an arm and attempts to capture a shot for as long as its
supply remains high enough to operate.

## Feature Boundary

Implemented for this board:

- serial-derived `Chrono-XXXX` BLE names;
- app nickname, address, RSSI, last-connected marker, and Flash LED command;
- firmware-owned pre-arm port health and arm refusal;
- START-first hardware gating, timeout, range checks, and timing fault logs;
- per-logger calibration/readiness records with a 30-day age limit;
- simulation fault selection;
- diagnostic/result export with device and raw timing metadata;
- filtered battery reporting, per-result voltage, and a warning-only low-battery state.
- low-leakage System OFF with debounced D4 long-press shutdown and wake.

Deferred because this PCB lacks the required hardware or validation:

- dock, charging-contact, and waterproof charging diagnostics;
- four timing channels and buffered threshold inputs;
- BLE bonding or a physical remote-arm confirmation policy;
- flash-journaled pending results across complete logger power loss.
- Android foreground BLE service and background reconnection;
- sensor inventory, matched-pair management, saved templates, and series
  statistics/outlier analysis.

The last item is a firmware/storage task rather than a schematic change, but it
should be implemented only after the XIAO flash filesystem and write endurance
behavior are tested on the actual board. Until then, pending results survive a
BLE loss but not complete logger power loss.

## Bring-Up Checks

1. Confirm D4 reads high normally and low while the button is pressed. Verify a
   short press has no effect, a 1.5-second hold powers off, and only another
   complete 1.5-second hold restores normal operation.
2. Confirm D5 drives the external LED without using the onboard LED mapping:
   one short heartbeat every 2 seconds while idle, a rapid repeating blink
   while armed, flashing while the power button is held, three quick power-on
   flashes, and two slower power-off flashes followed by darkness.
3. Scope both raw junctions and sense junctions during soft and hard strikes.
4. Confirm the P4KE15CA and both 1N5711 orientations from the schematic/netlist.
5. Run empty and loaded RC checks on both channels.
6. Deliberately short each input, disconnect each sensor, and couple the two
   channels to verify the corresponding diagnostic flags.
7. Inject STOP first and omit STOP after START to verify both timing faults.
8. Verify a valid START/STOP pulse pair still captures at 62.5 ns resolution.
