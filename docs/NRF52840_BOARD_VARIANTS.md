# nRF52840 Board Variants for PCB Layout

This is the current layout target for the two Chrono hardware variants:

1. A low-cost two-channel nRF52840 board with unbuffered sensor inputs.
2. A four-channel nRF52840 board with high-speed buffered sensor inputs.

Both variants should keep the existing BLE/app direction and the hardware timing
principle: sensor edge -> GPIOTE event -> PPI -> TIMER capture. The input
front-end changes, but shot timing should still be captured in hardware instead
of by a CPU interrupt.

## Variant Summary

| Variant | Channels | Input stage | Primary purpose |
|---|---:|---|---|
| Two-channel unbuffered | 2 | Piezo node clamped directly into nRF52840 GPIO | Lowest-cost board and current firmware/app baseline |
| Four-channel buffered | 4 | Protected analog node into high-speed buffer or comparator, then nRF52840 GPIO | Better-defined timing edge, channel diagnostics, and future tighter uncertainty model |

## Shared Layout Rules

Use these rules on both boards:

1. Keep every sensor channel physically matched: same component values, same
   orientation, same connector-to-front-end distance, and similar trace length.
2. Put the protection network at the connector/front-end area, not only at the
   MCU pin after a long raw sensor trace.
3. Keep piezo return current and clamp current paths short, with a nearby ground
   via at each clamp/protection group.
4. Keep raw sensor nodes away from the BLE antenna, USB/VBUS, battery charger,
   LED switching, and any long digital runs.
5. Use 3.3 V logic only on MCU pins. Do not route 5 V to any nRF52840 GPIO.
6. Give each channel test pads for at least raw sensor node, captured digital
   edge, ground, and 3V3.
7. Keep calibration charge pins true high impedance during live fire.
8. Do not tighten the app's uncertainty estimate for a new hardware revision
   until bench data proves the channel delay and edge repeatability.
9. Treat Arduino `D` labels as board-profile labels. Before layout, verify the
   exact nRF52840 module/board footprint and physical GPIO mapping.

## Variant A: Two-Channel Unbuffered Board

This is the current hardware/firmware baseline. The built PTH direction is
documented in `ECONOMICAL_TWO_CHANNEL_PTH.md`; its populated values take
precedence over the earlier tuning recommendations below.

| Function | Pin | Notes |
|---|---|---|
| CH1 START input | D0 | Direct clamped sensor node into GPIO |
| CH2 STOP input | D1 | Direct clamped sensor node into GPIO |
| CH1 charge/test | D2 | 10k 1% to CH1 sensor node |
| CH2 charge/test | D3 | 10k 1% to CH2 sensor node |
| Power/wake button | D4 | Momentary button to GND; internal pull-up; debounced 1.5-second hold for System OFF and wake |
| Status LED | D5 | External LED through 330 ohms, active-high |

Per-channel circuit:

```text
sensor connector A
    |
    +-- P4KE15CA TVS to GND and 470k bleeder to GND
    |
    +-- 1k protection resistance -- sensor node -- nRF52840 input
                                           |
                                           +-- Schottky clamp to 3V3
                                           |
                                           +-- Schottky clamp to GND
                                           |
charge/test GPIO -- 10k 1% resistor -------+

sensor connector B -> GND
```

Layout notes:

1. Copy the two channels as mirrored twins. Avoid one channel taking a shortcut
   while the other snakes around the board.
2. Populate 1k for the economical PTH build and validate it with the installed
   P4KE15CA and 1N5711 clamps.
3. Keep the Schottky clamp loop small and decouple 3V3 close to the clamp area.
4. Make the D2/D3 charge path easy to probe, because the app uses loaded-minus-
   bare RC signature as the practical sensor check.
5. Preserve the existing `HW_REV = 1` behavior unless the board shape or
   electrical timing changes enough that the app needs a separate profile.
6. See `docs/PIEZO_INPUT_PROTECTION.md` for the clamp/current-limit rationale.

This board should remain conservative in the app. Its timing is excellent at the
digital capture layer, but the live trigger point still depends on piezo strike
shape, GPIO threshold, clamp behavior, and sensor matching.

## Variant B: Four-Channel High-Speed Buffered Board

The four-channel board should keep the nRF52840 as the BLE/timing controller but
move the sensor threshold decision into a matched buffer/comparator stage.

Recommended pin map:

| Channel | Buffered output to MCU | Charge/test pin | Notes |
|---:|---|---|---|
| 1 | D0 | D4 | Normal start reference |
| 2 | D1 | D5 | Offset reported relative to CH1 |
| 3 | D2 | D8 | Offset reported relative to CH1 |
| 4 | D3 | D9 | Offset reported relative to CH1 |

Support pins:

| Use | Pin | Notes |
|---|---|---|
| User button / wake | D6 | Button to GND with pullup, or sealed magnetic equivalent |
| Dock detect / spare | D7 | Keep available if the charging dock needs detection |
| External LED / service signal | D10 | Optional; prefer onboard LED/light pipe when possible |

Buffered per-channel topology:

```text
sensor connector A
    |
    +-- input protection / current limit -- analog sensor node
                                               |
                                               +-- clamp/TVS network
                                               |
                                               +-- high-speed buffer/comparator input
                                               |
charge/test GPIO -- 10k 1% resistor -----------+

buffer/comparator output -- optional 22R-100R series -- nRF52840 capture input

sensor connector B -> GND
```

Prefer a comparator-style front-end when the goal is tighter timing: it gives a
defined threshold, allows controlled hysteresis, and makes per-channel delay
calibration meaningful. A simple high-speed logic buffer can work only if its
input protection, threshold tolerance, and over/under-voltage behavior are
acceptable for the protected piezo node.

Four-channel layout notes:

1. Put the four analog front-ends in a repeated row or symmetric block before
   routing to the MCU. Do not mix channel order between connector labels and MCU
   pins.
2. Use one shared, well-decoupled threshold/reference network for all channels
   unless testing shows each channel needs trimming.
3. Add resistor footprints for hysteresis and input filtering, even if some are
   DNP on the first prototype.
4. Add an output test pad per channel after the buffer/comparator and before the
   MCU input. This is the easiest point to verify known-delay pulses.
5. Keep comparator/buffer supply decoupling tight: one local 0.1 uF per package,
   plus a nearby bulk capacitor for the input-front-end area.
6. Keep raw analog nodes compact. Buffered digital outputs can travel farther;
   raw piezo nodes should not.
7. Bench-calibrate channel delay as:

```text
channel_offset[ch] = measured_capture[ch] - measured_capture[1]
corrected_offset[ch] = raw_offset[ch] - channel_offset[ch]
```

## Firmware And App Implications

The existing firmware is two-channel. The four-channel buffered board needs a
clear hardware-revision split instead of pretending to be the current board.

Recommended firmware direction:

1. Keep two-channel unbuffered hardware as `HW_REV = 1`.
2. Assign the four-channel buffered board a new hardware revision.
3. Extend the hardware info packet with channel count and input-stage type when
   the BLE protocol is revised.
4. Use four GPIOTE event channels, four PPI channels, and TIMER2 capture
   registers CC0-CC3 for the four timing inputs.
5. Capture the first rising edge on each active channel and disable that
   channel's capture after the first edge to reject ringing.
6. Store a valid mask so partial shots and timeout cases can be diagnosed.
7. Report raw offsets for channels 2-4 relative to channel 1, plus the hardware
   revision and timing model used for the result.

Recommended app direction:

1. Keep the current two-channel flow for `HW_REV = 1`.
2. Add a four-channel mode for the buffered board.
3. Support equal-spacing and per-segment spacing entry.
4. Log raw channel offsets, segment velocities, overall velocity, sensor IDs,
   photos, and the calibration/offset data used for the uncertainty estimate.
5. Only show a tighter confidence/GAE number after the buffered board has
   measured channel-delay and repeatability data.

## Schematic Freeze Checklist

Before starting final PCB layout, settle these items:

1. Exact nRF52840 module or board footprint for each variant.
2. Sensor connector style and channel order.
3. Battery, charging, and programming access plan.
4. Exact buffer/comparator part and footprint for the four-channel board.
5. Comparator threshold and hysteresis values.
6. Whether the four-channel charge/test path measures the analog sensor node
   through the same threshold device used for live shots.
7. Test-pad locations for raw node, buffered output, threshold/reference, 3V3,
   GND, battery, and SWD/programming.
8. Hardware revision numbers and BLE compatibility plan.

The safest first layout path is to keep the two-channel board as the known-good
direct-input reference, then build the four-channel buffered board with enough
probe points to prove each extra assumption on the bench before tightening the
app's accuracy model.
