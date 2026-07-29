# Chrono — Hardware (economical build)

The **economical build** of the Chrono chronograph: a Seeed Studio **XIAO nRF52840**
plus a passive protection front‑end per sensor, timing captured entirely in the
nRF52840's hardware (GPIOTE → PPI → TIMER2 @ 16 MHz, 62.5 ns). No external
time‑to‑digital converter.

This is the low‑cost, field‑useful path. Expected timing contribution from the
electronics is **< 0.1 %** at typical splits (125 µs–1 ms, i.e. 1000–4000 fps over
6–12 in) and **~0.5 %** at the 12 kfps / 2 in extreme — accuracy there is limited by
sensor matching and gate‑spacing measurement, not the board. A higher‑precision
variant (external TDC7200 front end) is noted at the end as a future upgrade.

Board: `ChronoPcb2chPTH` (2‑channel, through‑hole passives). Two sensor gates
(START + STOP) is the whole chronograph.

---

## Per‑port front‑end

Each sensor port is a piezo input protected by a two‑stage clamp. The TVS bounds
the high‑voltage piezo spike at the connector; the 1 kΩ limits current; low‑cap
Schottky diodes do the precise final clamp right at the MCU pin.

```
 PIEZO+ ─┬────────┬────────┬───[1k]───┬──────────┬────► NODE ──► MCU sense
         │        │        │          │          │
       [470k]  [TVS]     (—)       [D_hi]      [D_lo]
        bleed  P4KE15CA           →3V3        NODE→
         │        │        │          │          │
 PIEZO- ─┴────────┴─── GND         GND        (anode GND)

        MCU charge ──[10k 1%]── NODE      (calibration charge path)
```

**Netlist (port n):**

| Net | Members |
|---|---|
| `PIEZOn_HI` | connector·1 · R_bleed·a · TVS · R_series·a |
| `NODEn` (sense) | R_series·b · R_cal·b · D_hi anode · D_lo cathode · MCU sense pin |
| `CHGn` (charge) | R_cal·a · MCU charge pin |
| `3V3` | D_hi cathode (+ decoupling) |
| `GND` | connector·2 · R_bleed·b · TVS · D_lo anode |

Clamp orientation: **D_hi** anode→NODE, cathode→3V3; **D_lo** anode→GND, cathode→NODE.
The TVS is bidirectional (no polarity) and must sit **at the connector** so the
connector pins never see more than the clamped ~20 V.

---

## BOM

**Per port (×2):**

| Ref | Part | Value / MPN | Notes |
|---|---|---|---|
| J2/J3 | Connector | JST XH 2‑pin (B2B‑XH‑A) | 250 V rating; moot behind the TVS |
| R_bleed | Resistor | **470 kΩ** | baseline stability; do NOT drop below ~100 k |
| TVS | Bidirectional TVS | **P4KE15CA** | clamps piezo spike to ~20 V; low‑cap |
| R_series | Resistor | **1 kΩ** | limits clamp current (~16 mA worst case) |
| D_hi, D_lo | Schottky ×2 | **1N5711** (see sourcing note) | low‑cap (~2 pF) for timing; keep at the pin |

> **1N5711 sourcing (2026):** the axial DO-35 1N5711 is obsolete at authorized
> distributors (ST EOL July 2025; the remaining Microchip JAN parts cost $6+).
> Options, in order of preference:
> 1. **1N5711W-7-F** (Diodes Inc., SOD-123, Active, ~$0.21, deep DigiKey stock)
>    — the same 70 V / 15 mA / 2 pF spec in a large, easily hand-soldered SMD.
>    Give the board a dual DO-35 + SOD-123 footprint so either fits.
> 2. **BAS40-05 / BAS40-04** (SOT-23 dual, ~5 pF, Active) — one package per
>    channel replaces both clamps; same family used on the advanced board.
> 3. Legacy DO-35 stock from Amazon/eBay is acceptable for prototypes — and the
>    app's bare-port baseline sweep doubles as a counterfeit check: a fake or
>    relabeled part shows up as an elevated capacitance signature and/or a
>    shifted baseline offset versus the expected values.
| R_cal | Resistor | **10 kΩ 1 %** | RC calibration reference (tolerance matters) |

**Shared:**

| Ref | Part | Value / MPN | Notes |
|---|---|---|---|
| U1 | MCU module | Seeed XIAO nRF52840 | 16 MHz hardware timing capture |
| J1 | Battery | JST XH 2‑pin | to BATT+/BATT− |
| C (0.1 µF) | Ceramic | 0.1 µF X7R, 50 V | HF decoupling, at U1 3V3 pin |
| C (bulk) | Ceramic | **TDK FG26X7R1E106KRT06** (10 µF 25 V X7R radial) | clamp‑injection reservoir; in stock DigiKey |
| D7 | LED | red | status |
| R7 | Resistor | 330 Ω | LED limit |
| SW1 | Button | momentary | to GND (needs firmware pull‑up if used) |

All decoupling is **ceramic** on purpose — nanoamp leakage. Avoid tantalum/electrolytic
bulk (µA leakage) if battery standby matters. Rate the bulk **16–25 V** (not 6.3 V)
so DC‑bias derating doesn't eat the capacitance.

---

## Pin assignment (matches the firmware)

The firmware hardcodes these; keep them:

| Port | Sense (GPIOTE capture) | Charge (cal drive) |
|---|---|---|
| 1 — START | **D0** (P0.02) | **D2** (P0.28) |
| 2 — STOP | **D1** (P0.03) | **D3** (P0.29) |

Also: **D5** = status LED, **D4** = button. Start/stop is just which gate you plug
in first, so the physical START vs STOP connector can be whichever routes cleaner.

---

## PCB rules

Net classes and DRC floor are provided as loadable files:

- **`chrono_netclasses.scr`** — run in the schematic (File → Execute Script) to create
  the classes.
- **`chrono.dru`** — load in the board (Tools → DRC → Load) for the global floor.

| Class | Nets | Width | Clearance |
|---|---|---|---|
| Power | 3V3, BATT+/−, 5V | 0.40 mm | 0.20 mm |
| Sensor_HV | PIEZOn_HI (N$1, N$2) | 0.30 mm | 0.40 mm |
| Timing | NODEn (D0, D1) | 0.25 mm (short!) | 0.30 mm |
| Signal | charge, LED, button, unused | 0.20 mm | 0.20 mm |
| GND | GND | pour + 0.40 mm | 0.20 mm |

Assign nets to classes manually (Eagle stores this in the design): Power → 3V3/N$3/N$4/N$11,
Sensor_HV → N$1/N$2, Timing → D0/D1, GND → GND, rest → Signal.

---

## Layout guidance (in priority order)

1. **XIAO antenna keep‑out — critical.** No ground pour or traces under the module's
   onboard antenna (the end opposite USB‑C). Overhang the board edge or add a copper
   keep‑out on both layers. Flooding under the antenna detunes it and gnaws BLE range.
2. **Ground pour both layers, via‑stitched.** Clean returns + shields the two timing
   nodes from each other.
3. **Clamps at the pin.** Put the 1N5711s hard against D0/D1; keep NODE short.
   Capacitance on this net is the timing enemy — short + narrow beats wide.
4. **Channel symmetry.** Mirror the two ports so NODE1 and NODE2 have matched
   parasitics — the chronograph measures their *difference*, so matched beats short.
5. **Decoupling placement.** 0.1 µF closest to U1's 3V3 pin; bulk near where the upper
   clamps (D3/D6) tie into 3V3.
6. **TVS at the connector** so the connector pins stay in a ~20 V world.

---

## Why these values (design rationale)

- **Capacitance is the timing enemy.** Every pF on NODE blunts the edge and widens
  time‑walk. That's why the clamp diodes are low‑cap 1N5711 (~2 pF) not BAT85 (~10 pF),
  and why NODE stays short/narrow.
- **The TVS is what *lets* us use the low‑cap diode.** It bounds the surge to ~16 mA,
  so the small 1N5711 is safe. Drop the TVS and you'd need BAT85 (5× the cap) + a 2.2 k
  series — worse timing on both counts. We kept the TVS.
- **470 k bleed** stabilizes the DC baseline (each shot starts from 0 V) without loading
  the fast edge or dragging the calibration ramp below its trip threshold. Lower values
  load the piezo and break the cal; higher values raise leakage‑offset drift.
- **Trigger fires at ~2.3 V**, far below any clamp — so clamp voltage never affects
  *when* we trigger, only capacitance does. That frees the TVS to be chosen for peak
  reduction and the clamps for low capacitance.

---

## Files in this folder

- `README.md` — this document
- `chrono_netclasses.scr` — Eagle/Fusion net‑class definitions
- `chrono.dru` — Eagle/Fusion design rules (clearance/size floor)
- `ExportedParameters.csv`, `Netlist` — netlist exports from the board (reference)

---

## Future: precision variant

For lab‑grade accuracy at the 12 kfps / 2 in extreme, the timing element would move off
the nRF52840 (16 MHz / 62.5 ns ceiling) to an external **TDC7200** time‑to‑digital
converter (sub‑ns), with the XIAO demoted to SPI reader + BLE bridge, and matched fast
comparators on the piezo front end. That's the precision build; this repo documents the
economical one.
