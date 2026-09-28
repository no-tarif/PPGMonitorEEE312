# SpO2 method (app v3)

Engineering estimate from dual-wavelength PPG; **not for medical use**.
Code: `dsp/spo2/` (engine, config, live tracker/guard), `history/ReadingPostProcessor.kt`
(saved reading), `tools/spo2_reference.py` (Python reference; the Kotlin engine matches
it to 1e-6 on the sep16 golden tables).

## 1. Signal chain

```
MAX30105 slot 1 = red (660 nm), slot 2 = IR (880 nm)
  -> PpgStream.ino line  ir,red,accX,accY,accZ,green,seq   (ir FIRST, red SECOND)
  -> SerialLineParser    fields[0] -> ir, fields[1] -> red
  -> SensorSample.ir / .red -> DropoutFiller (flags only; SpO2 uses the RAW received values)
  -> SampleEntity.irRaw / redRaw  -> Spo2Engine(ir = irRaw, red = redRaw)
```

SpO2 never uses the LMS-cleaned / normalised heart-rate signal: the ratio of ratios needs
each channel's own untouched DC level (`estimate_spo2_from_table.m`).

## 2. Per-window computation

After a 30 s warm-up (sensor settling; shrunk if the recording is short), both raw channels
are linearly detrended and band-passed 0.5–4 Hz with the **same** order-4 Butterworth filter
run forward and backward (zero phase, `sosfiltfilt`), so the two channels get identical gain
and phase. The coefficients are the precomputed tables at 25 Hz and 16.67 Hz; any other rate
gets the same design computed for the measured rate (`ButterworthDesign`, checked against
scipy). Windows are 12 s long, one every 2 s (10 s overlap), as in MATLAB.

For each window: `DC = mean(raw)`, `AC = SD(band-passed)` (N−1), `PI = AC / DC`,

```
      PI_red     AC_red / DC_red
R  =  ------  =  ---------------          SpO2 = 110 − 25·R
      PI_IR      AC_ir  / DC_ir
```

**Constants.** `110 − 25R` is the generic textbook line used by `estimate_spo2.m` /
`config.m` (`cfg.spo2.calibration = 'linear'`); the "CALIBRATED" MATLAB project has no
device-fitted constants — its calibration is the optional one-point offset (section 5). The
quadratic curve from the CEEMDAN/VS-LMS paper (`−22.53R² + 1.4389R + 103.78`) is available but
not the default. Both are uncalibrated for this hardware. Sums are done in `Double`, so 18-bit
counts cannot overflow.

## 3. Quality gates (a window counts only if it passes all, in this order)

| Gate | Rule | Reason code |
|---|---|---|
| Dropouts | any filled / lost / inserted / settling / saturated / low-signal sample in the window | `DROPOUT` |
| ADC rail (live rig) | any raw value ≥ 262 000 | `SATURATED` |
| DC | either DC ≤ 0 | `NON_POSITIVE_DC` |
| Finger off (live rig) | DC_IR < 50 000 counts | `FINGER_OFF` |
| Perfusion | either PI < 0.05 % | `LOW_PERFUSION` |
| Red–IR agreement | Pearson r(band-passed red, band-passed IR) < 0.8 | `LOW_CORRELATION` |
| Motion | SD(|acc|) > 10 % of the recording's median |acc| (= gravity, any unit) | `MOTION` |
| Beats | < 6 pulse peaks (positive maxima ≥ 0.27 s apart) in the band-passed IR | `TOO_FEW_BEATS` |
| Plausible R | R outside 0.3–3.0 | `R_OUT_OF_RANGE` |
| Curve | SpO2 > 100 % / < 70 % | `ABOVE_100` / `BELOW_70` |

R is still stored for windows rejected by the last five gates, so it can be inspected. A value
above 100 % is **rejected but not hidden**: the window keeps its raw value (a hollow point above
the 100 % line in History) and the count and maximum are logged. On sep16 (the regression
capture) none of the v3 gates fires; 70 of 83 windows are rejected as `ABOVE_100` because the red
channel is weak (R ≈ 0.37), exactly as before.

## 4. Summary and live value

- **Saved reading:** SpO2 = **median of the valid windows only**. Fewer than 3 valid windows →
  "N/A (fewer than 3 valid windows)"; none → "N/A (no valid windows)". No red channel →
  "N/A (no red channel)". A reading whose red column has a few blanks is still processed; only
  the windows around the blanks are dropped.
- **Live tile:** needs two agreeing windows before showing a value; then shows the mean of the
  last 3 accepted windows. A single-window jump of more than 3 points is held until the next
  window confirms it (else dropped). Two invalid windows in a row, any window with dropouts, or
  signal lost → "--" immediately (no stale value). Never shown above 100 %.

## 5. Calibrating against a commercial finger oximeter (new rig)

1. Balance the LEDs first with `PiBalance.ino` (guide §6): PI_IR 0.5–2 %, red within 2× of IR,
   R 0.4–1.0. Calibrating a rig with R < 0.4 only hides a weak red channel.
2. **Check red/IR aren't swapped:** at rest R must be about 0.4–0.8 (SpO2 ≈ 90–100 %). R > 1.2
   with good perfusion means the channels are swapped (the app's SpO2 diagnosis says so).
3. Oximeter on a finger of the other hand; record **120 s completely still**, hand at heart level,
   sensor covered. Note the oximeter's stable reading (e.g. 98 %) and the time span (e.g. 60–100 s).
4. History → the reading → Options → *Calibrate against reference oximeter…*: enter the value and,
   optionally, the time span. The app computes `R_cal = median R` over those windows and shifts the
   curve so `curve(R_cal) = reference` — the **slope stays the generic −25 per unit R**.
5. Report it as "one-point offset calibration, slope unverified". It makes the device read normal
   **by construction** at that saturation; it does not show the rig would detect a real
   desaturation (that needs several saturation levels — a clinical procedure). Repeat on 3+ days:
   if the offset is stable (± 1 %), the rig's error is a steady offset rather than scatter.
