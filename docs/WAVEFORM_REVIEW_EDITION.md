# Chrono Waveform Review Edition

This repository is the existing-hardware waveform edition of Chrono. It runs on
the same two-channel XIAO nRF52840 PTH logger and original nice!nano logger. No
PCB or sensor wiring changes are required.

The logger still preserves the first CH1 rising edge and first CH2 rising edge
in hardware for an automatic result. In parallel, it records the observable
high/low transitions from both protected GPIO inputs. The Android app renders
those transitions as aligned digital waveforms and lets the user choose the
START and STOP rising edges that best represent projectile impact.

## What The Trace Is

The trace is a threshold-crossing or logic-analyzer view:

```text
CH1  ____|---|_|-|________________|-----|_|-|____
          first disturbance        impact/ringing

CH2  _______|-|________________________|---|_|-|_
```

It shows when each protected input was below or above the nRF52840 GPIO
threshold. It does not measure piezo voltage or impact energy. If air shock and
impact produce identical threshold crossings, an analog front end or external
oscilloscope is still required to distinguish them.

## Firmware Behavior

- GPIOTE is configured for any input change on D0 and D1.
- PPI preserves the first accepted CH1/CH2 timestamps in TIMER2 CC0/CC1.
- Separate PPI channels capture each later transition before the GPIOTE
  callback copies it into the trace buffer.
- The trace continues for 250 ms after CH1 starts, or until the normal timeout.
- Each retained result stores up to 256 interleaved transitions.
- A transition uses a 24-bit timer offset plus channel/level metadata.
- Trace flags report buffer overflow, suspected missed transitions, and CH2
  activity before CH1.
- Up to 16 unacknowledged results and their traces remain in RAM across BLE
  disconnects. Power loss still clears this RAM, as in the original build.

The automatic first-edge timestamp remains a hardware capture. Later trace
edges are hardware-timestamped but must be copied by an interrupt before the
same channel changes again. The app therefore treats trace-quality flags as
part of the measurement evidence.

## BLE Additions

The existing result packet remains compatible. Waveform firmware reports
capability bit `0x0008` and adds:

| Item | Value |
|---|---|
| Trace characteristic | `a5c40009-9d95-4e4c-8c5a-c1d6f2a80de1` |
| Fetch-trace command | `12`, with the result ID in the command argument |
| Trace format | Version 1 |
| Events per notification | Up to 40 |
| Integrity | CRC-16/CCITT on every chunk |

Each chunk contains the result ID, format and trace flags, chunk position,
total event count, base timer tick, packed events, and CRC. The phone requests
the trace after receiving the base result. It sends the normal result ACK only
after the complete waveform has been stored.

## App Review Workflow

1. Receive the automatic result.
2. Download and CRC-check all waveform chunks.
3. Open **Review waveform**.
4. Tap a CH1 rising edge for START and a CH2 rising edge for STOP.
5. Use previous/next controls for one-edge precision.
6. Pan or pinch/zoom, or select **Fit all**.
7. Review signed delta time, timer ticks, distance, velocity, and the difference
   from the automatic result.
8. Apply the reviewed time or retain the automatic result.

The raw automatic split is never overwritten. Stored results contain both the
automatic and reviewed values, selected cursor offsets, review timestamp,
packed trace, trace flags, and trace format.

Exports include the normal results CSV plus a waveform CSV with every event,
channel, level, timer offset, and nanosecond offset.

## Validation Checklist

- Compare captured transitions with a bench oscilloscope on both inputs.
- Exercise isolated pulses, close double pulses, and sustained ringing.
- Confirm overflow and suspected-loss flags appear when expected.
- Verify first-edge CC0/CC1 values never change during ringing.
- Disconnect BLE during a shot, reconnect, fetch the result and trace, then ACK.
- Test a trace with zero, one, 256, and overflowed event counts.
- Review on small portrait, large portrait, and landscape Android screens.
- Verify cursor snapping and signed velocity using simulated traces.
- Confirm XIAO and nice!nano firmware builds and the Android debug APK build.
