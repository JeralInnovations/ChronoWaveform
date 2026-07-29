# Advanced Build — Four-Channel Buffered (Window-Comparator) Board

Full component and connection plan for **Variant B** of
`NRF52840_BOARD_VARIANTS.md`: the four-channel buffered board. This document
settles the open items from that doc's Schematic Freeze Checklist (comparator
part, thresholds/hysteresis, presence-check path) and adjusts the front-end so
the board is **safe and accurate with any reasonable piezo sensor** — brass
discs, PVDF film, shielded shock sensors, large industrial elements — plugged
in **either polarity**.

Battery, pogo charging, enclosure, and sleep strategy carry over unchanged from
`FOUR_CHANNEL_WATERPROOF_BUILD.md`. Protection rationale carries over from
`PIEZO_INPUT_PROTECTION.md`.

---

## Key decisions (what changed vs. the economical board)

| Decision | Choice | Why |
|---|---|---|
| Trigger element | **Window comparator** (dual comparator per channel), not raw GPIO | Defined, adjustable threshold; kills GPIO time-walk; fires on **either polarity** so sensor wiring/orientation stops mattering |
| Comparator | **TLV3502** (dual, 4.5 ns, rail-to-rail I/O, ~6 mV internal hysteresis) | Fast enough that prop-delay dispersion is ns-level; one dual package = one window per channel; SOIC-8 hand-solderable |
| Polarity combine | **74LVC1G32 OR gate** per channel | One clean digital edge per channel into GPIOTE; keeps the 4-channel pin budget on a XIAO |
| Input conditioning | **AC-coupled, biased to mid-rail (VREF = 1.65 V)** | Any piezo, any polarity, no DC drift; baseline is set by VREF, not the sensor |
| Threshold (sensitivity) | **Firmware-adjustable** via two PWM-filtered rails (VTH_HI / VTH_LO, symmetric about VREF) | One app "sensitivity" setting spans small film sensors to big discs (≈±25 mV to ±1.5 V) |
| AFE power | **P-FET high-side gate** (AFE_EN) | 4× TLV3502 ≈ 26 mA; gated off outside verify/cal/armed so idle current stays at µA |
| Sensor presence check | **One shared TEST line** injecting through 100 k into each channel's input node; time-to-comparator-trip measured by the existing capture path | Preserves the app's attach/capacitance-check flow with zero extra pins; all 4 channels measured in one sweep |
| MCU / module | **Seeed XIAO nRF52840** (matches the four-channel waterproof build) | Timing capture unchanged: comparator edge → GPIOTE → PPI → TIMER2 CC0–CC3 |

---

## Per-channel signal chain

```text
J (2-pin, either polarity OK)
 PIEZO_A ──┬──────┬──────[R_ser 1k]──● IN_n ──[C_c 100nF/100V]──● AFE_n
           │      │                  │                          │
        [1M bleed] [SMBJ15CA TVS]  [R_test 100k]             [1M bias to VREF]
           │      │                  │                          ├─[BAS40-04 clamp: GND↔AFE_n↔AFE_3V3]
 PIEZO_B ──┴──────┴── GND         TEST line                     │
                                                     ┌──────────┴──────────┐
                                                     │ TLV3502 (per channel)│
                                                     │ A: +AFE_n  −VTH_HI   │──┐
                                                     │ B: +VTH_LO −AFE_n    │──┤ 74LVC1G32
                                                     └──────────────────────┘  └─► CAPT_n → MCU GPIOTE
```

Idle: AFE_n sits at 1.65 V; both comparator outputs low; OR output low.
Any excursion beyond **VREF ± Δ** (positive *or* negative) drives one comparator
high → OR output rises → GPIOTE captures TIMER2 in hardware. The existing PPI
self-disable-on-first-edge rejects the bipolar ring after the first crossing.

### Per-channel connections

| From | Component / value | To |
|---|---|---|
| J pin A | — | `PIEZOn_HI` |
| J pin B | — | GND |
| `PIEZOn_HI` | 1 MΩ bleed | GND |
| `PIEZOn_HI` | SMBJ15CA (bidirectional TVS) | GND |
| `PIEZOn_HI` | 1 kΩ series | `IN_n` |
| `IN_n` | 100 kΩ | `TEST` (shared line, MCU pin) |
| `IN_n` | 100 nF / 100 V X7R coupling | `AFE_n` |
| `AFE_n` | 1 MΩ bias | `VREF` (1.65 V) |
| `AFE_n` | BAS40-04 (series dual Schottky): bottom anode→GND, junction→`AFE_n`, top cathode→`AFE_3V3` | rails |
| `AFE_n` | TLV3502 **A: IN+**, **B: IN−** | comparator |
| `VTH_HI` | TLV3502 **A: IN−** | comparator |
| `VTH_LO` | TLV3502 **B: IN+** | comparator |
| TLV3502 A.OUT | 74LVC1G32 input 1 | OR |
| TLV3502 B.OUT | 74LVC1G32 input 2 | OR |
| 1G32 OUT | (optional 47 Ω series) | `CAPT_n` → MCU |
| TLV3502 V+ / 1G32 VCC | `AFE_3V3` + 100 nF each at pin | switched rail |

Test pads per channel (per the variants doc): `IN_n`, `AFE_n`, comparator A/B
outputs, `CAPT_n`.

---

## Shared circuits

**VREF (1.65 V):** 10 k / 10 k divider from `AFE_3V3` to GND, junction = VREF,
with 10 µF + 100 nF to GND. Serves all four channels' bias resistors (they load
it by 1 MΩ each — negligible).

**Threshold rails:** two MCU PWM outputs, each through a two-stage RC
(10 k + 1 µF, then 1 k + 100 nF) → `VTH_HI` and `VTH_LO`. Firmware holds them
symmetric: `VTH_HI = 1.65 V + Δ`, `VTH_LO = 1.65 V − Δ`. PWM ≥62.5 kHz keeps
residual ripple <1 mV. Shared by all channels so sensitivity is identical
across channels (this also preserves channel matching).

**AFE power gate:** DMP2305U (P-FET, SOT-23). Source→3V3, Drain→`AFE_3V3`,
Gate→`AFE_EN` with 100 k pull-up to 3V3 (default off). MCU drives low to power
the front-end. 10 µF + 100 nF on `AFE_3V3`. Everything analog (comparators, OR
gates, VREF divider) lives on the switched rail; ~26 mA when armed, ~0 when
idle/asleep.

**Presence/self-test (replaces the RC capacitance sweep):** the MCU raises the
shared `TEST` line; each `IN_n` ramps at a rate set by its own load (open port
≈ hundreds of pF from the TVS ⇒ fast; sensor attached ⇒ its capacitance slows
the ramp 20–1000×). The coupled step at `AFE_n` trips comparator A, GPIOTE
captures the time — the **same firmware capture machinery as live shots**, all
four channels in one sweep. During the test, firmware sets Δ to a fixed
reference (e.g. 100 mV). The app's attach flow (plug in → signature check →
tap test) is preserved; only the expected time ranges change with `HW_REV = 2`.

---

## XIAO pin map (11 GPIO — fits)

| Pin | Use |
|---|---|
| D0–D3 | `CAPT_1..4` (GPIOTE capture inputs from the OR gates) |
| D4 | `TEST` (shared presence-injection line) |
| D5 | `PWM_HI` → VTH_HI filter |
| D6 | `PWM_LO` → VTH_LO filter |
| D7 | `AFE_EN` (front-end power gate, active low) |
| D8 | Button / wake (to GND, internal pull-up) |
| D9 | Status LED (through 330 Ω) |
| D10 | Dock detect / spare |
| BAT pads | protected 1S LiPo (per waterproof build doc) |

---

## BOM

**Per channel (×4):**

| Part | Value / MPN | Package |
|---|---|---|
| Connector | JST XH 2-pin (B2B-XH-A) — polarity no longer matters | PTH |
| Bleed | 1 MΩ | 0805 |
| TVS | **SMBJ15CA** | SMB |
| Series | 1 kΩ | 0805 |
| Test inject | 100 kΩ | 0805 |
| Coupling | 100 nF, **100 V**, X7R | 0805 |
| Bias | 1 MΩ | 0805 |
| Clamp | **BAS40-04** (series dual Schottky, ~5 pF) | SOT-23 |
| Comparator | **TLV3502AID** (dual, 4.5 ns, RRIO, 6 mV hyst) | SOIC-8 |
| OR gate | **74LVC1G32** | SOT-353/SC70 |
| Decoupling | 100 nF ×2 (comparator, gate) | 0805 |
| Hysteresis/filter footprints | DNP on first spin (per variants doc) | 0805 |

**Shared:**

| Part | Value / MPN |
|---|---|
| MCU module | Seeed XIAO nRF52840 |
| AFE switch | **DMP2305U** P-FET + 100 k pull-up |
| VREF | 10 k ×2, 10 µF + 100 nF |
| VTH filters | (10 k + 1 µF) + (1 k + 100 nF) ×2 |
| Rail bulk | 10 µF 25 V X7R (e.g. TDK FG26X7R1E106KRT06 if PTH) + 100 nF |
| Battery / pogo / button / LED | per `FOUR_CHANNEL_WATERPROOF_BUILD.md` |

---

## Why this is safe with *any* reasonable piezo

1. **kV-class strikes**: TVS clamps the connector to ~20 V regardless of source
   size; 1 k limits current; BAS40-04 holds `AFE_n` inside the switched rail;
   the coupling cap is rated 100 V (5× the post-TVS worst case).
2. **Either polarity**: AC coupling + mid-rail bias + window detection — the
   sensor can be wired either way and can strike negative-first.
3. **Huge sensitivity range**: series-cap coupling into a high-Z node passes
   ≥85 % of the edge for any sensor ≥100 pF (film) up to ≥100 nF (large discs);
   the PWM threshold spans ±25 mV to ±1.5 V to match.
4. **DC-charged or leaky sensors**: the 1 M bleed drains standing charge; the
   bias network re-centers the node between events regardless of sensor DC
   behavior.
5. **Unpowered safety**: with the AFE gated off, the TVS + series R still bound
   any input event; nothing reaches the MCU directly (comparator in between).

---

## Expected timing performance (report conservatively first)

| Term | Economical (HW_REV 1) | Advanced (HW_REV 2) |
|---|---|---|
| Trigger time-walk | ~300 ns class (GPIO threshold vs. strike shape) | **≤10–50 ns** (5 mV offset ÷ ≥0.5 V/µs slew) |
| Comparator prop skew | — | ≤2 ns channel-to-channel |
| Timer quantization | 62.5 ns | 62.5 ns (unchanged) |
| Crystal | 30 ppm | 30 ppm |

Per the variants doc rule: **HW_REV 2 initially reports `edgeJitterNs = 150`**
(conservative) and is tightened toward ~50 only after bench data (known-delay
pulse into two channels, repeatability histogram). Even conservatively, typical
1000–4000 fps / 6–12 in shots become distance-measurement-limited, and the
12 kfps / 2 in extreme improves from ~±6 % to ~±2.5 % CI.

---

## Firmware deltas (HW_REV = 2 profile)

1. Four GPIOTE event channels → PPI → TIMER2 **CC0–CC3**; per-channel
   self-disable group on first edge; valid-mask for partial shots.
2. `HwInfo`: hwRev 2, channelCount 4, edgeJitterNs 150 (initial).
3. **CMD_SET_SENSITIVITY** (new): arg = Δ in mV → PWM duty for VTH rails; app
   re-sends on connect (no flash persistence needed).
4. Verify/tap mode becomes **event-based** (GPIOTE flag), not polled
   `digitalRead` — comparator pulses are too short to poll.
5. Presence check: drive `TEST`, capture all four time-to-trip values in one
   sweep with Δ set to the fixed reference; report per-channel signatures via
   the existing cal characteristic.
6. `AFE_EN` management: on for verify/cal/armed (+ short settling delay),
   off after idle timeout.
7. Channel-offset calibration storage per the variants doc
   (`channel_offset[ch]` relative to CH1).

## App deltas

- Four-channel mode per the variants doc (segments, equal/per-segment spacing,
  raw offsets in the log).
- **Sensitivity slider** (HW_REV 2 only), mapped to CMD_SET_SENSITIVITY.
- Attach-check expected ranges keyed by hwRev.
- CI model picks up the reported edgeJitterNs automatically (already built).

---

## Schematic-freeze checklist resolution

| Item (from NRF52840_BOARD_VARIANTS.md) | Resolution |
|---|---|
| 4. Buffer/comparator part | TLV3502AID + 74LVC1G32 per channel |
| 5. Threshold & hysteresis | PWM rails VREF ± Δ (25 mV–1.5 V), TLV3502 internal 6 mV hyst; DNP footprints for external hysteresis |
| 6. Charge/test path vs. live threshold device | Yes — TEST injection trips the **same comparator** used for live shots, measured by the same capture path |

Open for layout: exact connector order across the four ports, pogo pad
placement, and antenna keep-out (same rule as the two-channel board).
