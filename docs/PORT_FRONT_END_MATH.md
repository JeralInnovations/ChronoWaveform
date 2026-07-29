# Chrono Port Front-End and Timing Math

This document describes the current Chrono Logger port hardware, what the
firmware measures, and the predicted timing variability for the present
direct-to-GPIO piezo setup.

## Current Port Setup

Each Chrono channel uses one piezo sensor input and one calibration charge
output.

| Function | Channel 1 | Channel 2 | Notes |
|---|---:|---:|---|
| Sensor input | D0 | D1 | nRF52840 GPIO input, rising-edge trigger |
| Charge output | D2 | D3 | Drives the sensor node through 10 kohm during calibration only |
| Timing capture | TIMER2 CC0 | TIMER2 CC1 | Captured in hardware by GPIOTE/PPI |
| Timer rate | 16 MHz | 16 MHz | 62.5 ns per tick |

External front-end per channel:

```text
piezo +  ->  1 kohm protection resistor  ->  sensor node  ->  MCU input D0/D1
piezo -  ->  GND

sensor node  ->  Schottky clamp to 3.3 V rail
sensor node  ->  Schottky clamp to GND

charge GPIO D2/D3  ->  10 kohm 1% resistor  ->  sensor node
```

Live-fire timing uses only the sensor input path. The charge pin is returned to
high impedance outside calibration so it does not load the shot signal.

## Firmware Timing Path

The shot split is captured in hardware:

```text
sensor edge -> GPIOTE event -> PPI -> TIMER2 capture register
```

The CPU and BLE stack do not timestamp the edge. They only read the captured
timer values afterward.

```text
split_ticks = capture_stop - capture_start
split_ns = split_ticks * 62.5 ns
velocity = sensor_spacing / split_time
```

Fixed capture latency mostly cancels because both channels use the same
GPIOTE/PPI/TIMER path. What remains is edge-threshold behavior at the analog
front-end, cable/sensor mismatch, timer quantization, clock tolerance, and
spacing measurement error.

## Calibration / Port Baseline Math

During a port check, the firmware charges the sensor node from a 3.3 V GPIO
through the 10 kohm calibration resistor. The input edge is recorded when the
sensor node crosses the MCU's logic threshold.

Ideal single-node RC equation for intuition:

```text
Vnode(t) = VDD * (1 - exp(-t / (R * C)))

t_threshold = -R * C * ln(1 - Vthreshold / VDD)

C = t_threshold / (-R * ln(1 - Vthreshold / VDD))
```

For the current board:

```text
R = 10 kohm
VDD = 3.3 V
Vthreshold = unknown actual GPIO threshold
```

The actual loaded-sensor circuit is more complicated than that ideal equation
because the piezo capacitance is behind the 1 kohm protection resistor. With a
sensor installed, the measurement is a two-node RC network:

```text
charge GPIO -> 10 kohm -> MCU/sensor node -> 1 kohm -> piezo capacitance -> GND
```

The charge current also is not constant. It starts near:

```text
3.3 V / 10 kohm = 330 uA
```

and decays as the node charges. The ideal equation is useful for scale, but the
measured value should not be converted to an absolute capacitance unless the
whole fixture has been calibrated against known loads over the expected range.

If the threshold were exactly 0.5 * VDD:

```text
t_threshold = 0.693 * R * C
```

That means a true 30 nF load would cross threshold at roughly:

```text
0.693 * 10,000 ohm * 30 nF = 208 us
```

If the threshold were closer to 0.7 * VDD, the same 30 nF load would cross at:

```text
1.204 * 10,000 ohm * 30 nF = 361 us
```

This is why the app should treat the result as an RC trigger signature, not as
a lab-grade capacitance meter. The actual number depends on GPIO threshold,
diode leakage, board capacitance, cable capacitance, piezo behavior under DC
charge, and the fact that the drive is a 3.3 V RC step rather than a precision
constant-current source.

The useful calibration values are:

```text
bare_port[ch] = median threshold time with nothing plugged in
loaded_port[ch] = median threshold time with sensor and cable attached
sensor_signature[ch] = loaded_port[ch] - bare_port[ch]
channel_mismatch = abs(sensor_signature[1] - sensor_signature[2])
```

The signature is rigorous because it is measured by the same MCU threshold path
that detects the shot. It is not necessarily a true nF value. The Fluke-checked
loads showed this directly: the readings were not off by one fixed nF offset,
so reporting raw delay/signature is more defensible than reporting estimated nF.

## Effect of the 3.3 V Clamp

The 3.3 V clamp does not meaningfully distort the normal port baseline
measurement because the calibration GPIO only drives to 3.3 V. The RC charge
target is the same 3.3 V rail that the clamp references.

For a live piezo strike, the clamp matters mainly after the GPIO threshold has
already been crossed. A typical input threshold is below the upper clamp point,
so the first rising-edge detection usually happens before the Schottky diode is
conducting hard.

The clamp is still important for protection. A direct piezo strike can produce
large open-circuit voltage. Once the node tries to rise above roughly:

```text
VDD + diode_forward_drop
```

the excess current is shunted through the upper clamp. Approximate positive
clamp current is:

```text
I_clamp ~= (Vpiezo - 3.6 V) / 1 kohm
```

Examples:

| Open-circuit piezo pulse | Approximate clamp current pulse |
|---:|---:|
| 30 V | 26 mA |
| 100 V | 96 mA |
| 300 V | 296 mA |
| 500 V | 496 mA |
| 1000 V | 996 mA |

Those are brief pulse estimates, not continuous ratings. The main risk is rail
injection and threshold movement, not just pin overvoltage. Local decoupling,
short return paths, and an external protection stage or buffer would make the
front-end more predictable.

## Live-Strike Threshold Model

For a fast piezo pulse that behaves like a voltage step:

```text
t_cross = -Rin * Cin * ln(1 - Vthreshold / Vpeak)
```

For a slower pulse that rises approximately linearly from 0 to Vpeak over Tr:

```text
source_slope = Vpeak / Tr
Vpin(t) = source_slope * (t - tau + tau * exp(-t / tau))
tau = Rin * Cin
```

The GPIO triggers when:

```text
Vpin(t) = Vthreshold
```

The present front-end has roughly:

```text
Rin = 1 kohm protection resistor
Cin = MCU input + board + cable + clamp capacitance at the input node
sensor capacitance affects source behavior and calibration signature
```

For strong direct strikes, the threshold is crossed early and electronics-only
channel timing spread is expected to be about 0.1 to 0.5 us. For normal moderate
strikes, a conservative operating estimate is about +/-0.5 us per channel, or
about +/-1 us on the measured split. Weak or slow-rising strikes can move by
several microseconds or fail to produce a clean trigger.

## Expected Variability at 6 Inch Sensor Spacing

At 6 inch spacing:

```text
spacing = 0.5 ft
split_time = 0.5 ft / velocity_fps
velocity_error_percent ~= timing_error / split_time
```

Using the current expected normal-use timing variability of +/-1 us on the
measured split:

| Velocity | Split time | Timing-only variability | Velocity variability |
|---:|---:|---:|---:|
| 1000 fps | 500 us | +/-0.2% | +/-2 fps |
| 2000 fps | 250 us | +/-0.4% | +/-8 fps |
| 3000 fps | 166.7 us | +/-0.6% | +/-18 fps |
| 4000 fps | 125 us | +/-0.8% | +/-32 fps |
| 5000 fps | 100 us | +/-1.0% | +/-50 fps |

This table is the expected electronics/front-end timing contribution for clean
normal hits. It does not include sensor spacing error, projectile geometry,
sensor placement, or abnormal weak triggers.

Spacing error matters a lot at 6 inches:

| Spacing error | Velocity error at 6 inch spacing |
|---:|---:|
| 1/32 in | about +/-0.52% |
| 1/16 in | about +/-1.04% |
| 1/8 in | about +/-2.08% |

## Current App GAE Math

The app's Guaranteed Accuracy Envelope is intentionally more conservative than
the expected normal-use number. It combines independent uncertainty terms and
reports a roughly 99% style envelope using a 2.58 multiplier.

Current model:

```text
tick_sigma_ns = 62.5 ns / sqrt(12)
edge_sigma_ns = edge_jitter_ns * sqrt(2)
clock_sigma_ns = split_ns * clock_ppm / 1,000,000
front_end_sigma_ns = sqrt(cal_mismatch_term^2 + cal_repeatability_term^2)

sigma_time_ns = sqrt(
    tick_sigma_ns^2
  + edge_sigma_ns^2
  + clock_sigma_ns^2
  + front_end_sigma_ns^2
)

timing_envelope_relative = 2.58 * sigma_time_ns / split_ns
spacing_envelope_relative = user_measurement_error_m / spacing_m

GAE_relative = sqrt(
    timing_envelope_relative^2
  + spacing_envelope_relative^2
)

GAE_percent = GAE_relative * 100
```

The user enters Measurement Error as an already-expanded `+/-` value, using an
independent unit selector. It is therefore not multiplied by 2.58 a second
time. Because velocity
is proportional to spacing for a fixed split time, its relative contribution
is exactly `user_measurement_error / spacing`. The app combines that independent
contribution with the 99% timing envelope by root-sum-square.

Firmware revision 1 currently reports:

```text
timer tick = 62.5 ns
clock = 30 ppm
edge jitter allowance = 300 ns per edge
```

If calibration is missing, the app uses conservative defaults:

```text
cal_mismatch_term = 300 ns
cal_repeatability_term = 120 ns
front_end_sigma = sqrt(300^2 + 120^2) = 323 ns
```

That produces approximately:

```text
sigma_time = 533 ns
99% time envelope = 2.58 * 533 ns = 1.38 us
```

At 6 inch spacing, with default calibration assumptions and a user-entered
Measurement Error of `+/-0.02 in`, the app's approximate GAE is:

| Velocity | Split time | Approximate GAE | Velocity envelope |
|---:|---:|---:|---:|
| 1000 fps | 500 us | +/-0.43% | +/-4 fps |
| 2000 fps | 250 us | +/-0.64% | +/-13 fps |
| 3000 fps | 166.7 us | +/-0.89% | +/-27 fps |
| 4000 fps | 125 us | +/-1.15% | +/-46 fps |
| 5000 fps | 100 us | +/-1.42% | +/-71 fps |

With Measurement Error of `+/-0.25 in` over the same 6 inch gate, spacing alone is
`+/-4.17%`, so it dominates the combined GAE at ordinary shot velocities.

The GAE can narrow when channel signatures are closely matched and loaded
calibration repeatability is good. It widens when the split time is short, the
sensor spacing is short, channel signatures are mismatched, or calibration is
noisy.

## Practical Interpretation

1. The port check is best described as a repeatable RC signature check.
2. The live shot measurement is a hardware timestamp of the GPIO threshold
   crossing, not a CPU interrupt timestamp.
3. The 3.3 V clamp does not invalidate the calibration check, but live-shot rail
   injection can affect threshold stability.
4. For 1000 to 5000 fps over 6 inches, clean-hit timing variability is expected
   to be about +/-0.2% to +/-1.0% before spacing error.
5. With user-entered Measurement Error of `+/-0.02 in`, the app's GAE is roughly
   +/-0.4% to +/-1.4% over the same 1000 to 5000 fps range with default
   calibration assumptions. A wider entered range increases it directly.
6. A high-speed buffer or comparator with a defined threshold would reduce the
   analog front-end uncertainty and make the GAE less dependent on sensor strike
   shape and MCU input threshold behavior.
