"""
spo2_reference.py - straight Python port of estimate_spo2.m, used as the
ground-truth reference the Kotlin SpO2 engine (Phase 2, dsp/spo2/) must
match to within 1e-6. Also the tool used to generate the golden window
tables under app/src/test/resources/golden/.

ALGORITHM (mirrors estimate_spo2.m's structure section-for-section - see
that file's header for the full explanation of WHY):
  1. Warm-up skip: drop the first round(warmupSec*fs) samples (shrunk to
     fit if that would leave less than one window). Reported times stay
     relative to the ORIGINAL start.
  2. Linear detrend + 4th-order Butterworth band-pass (SOS, zero-phase
     forward-backward, i.e. scipy.signal.sosfiltfilt) on each of ir/red.
     DC always comes from the RAW (post-warm-up, pre-filter) signal.
  3. Sliding window (winSamples = round(windowSec*fs), step =
     round(stepSec*fs)): DC = mean(raw), reject if either DC <= 0;
     PI = std(bandpassed, ddof=1) / DC; reject if either PI < minPerfusion;
     R = PI_red / PI_ir (kept even if then rejected); reject if R outside
     validRRange; else SpO2 = curve(R).
  4. Optional one-point offset calibration: R_cal = median(finite R)
     (optionally restricted to a time range), offset = ref - curve(R_cal),
     added to every SpO2 (slope unchanged).
  5. Validity after calibration: 70 <= SpO2 <= 100.

Re-run standalone:  python tools/spo2_reference.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.signal import butter, detrend, sosfiltfilt

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "datasets"
GOLDEN_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "golden"

PROJECT_DIR = Path(r"E:\Study Materials\3-1\312\Project")
SEP16_CAPTURE_CSV = PROJECT_DIR / "sep16_capture.csv"

SEP16_SOURCE_FS_HZ = 1.0 / 0.06  # exactly 50/3 Hz


# ---------------------------------------------------------------------------
# Config (mirrors config.m's cfg.spo2.* defaults)
# ---------------------------------------------------------------------------

@dataclass
class SinglePointCalibration:
    enable: bool = False
    ref_spo2: float | None = None
    time_range: tuple[float, float] | None = None


@dataclass
class Spo2Config:
    calibration: str = "linear"       # 'linear' or 'quadratic'
    min_perfusion: float = 0.0005
    valid_range: tuple[float, float] = (70.0, 100.0)
    valid_r_range: tuple[float, float] = (0.3, 3.0)
    warmup_sec: float = 30.0
    band_hz: tuple[float, float] = (0.5, 4.0)
    window_sec: float = 12.0
    step_sec: float = 2.0
    single_point: SinglePointCalibration = field(default_factory=SinglePointCalibration)


def spo2_curve(r: np.ndarray | float, calibration: str) -> np.ndarray | float:
    if calibration.lower() == "quadratic":
        return -22.53 * r ** 2 + 1.4389 * r + 103.78
    return 110.0 - 25.0 * r


def band_hz_for_fs(band_hz: tuple[float, float], fs: float) -> tuple[float, float]:
    """Matches estimate_spo2_from_table's use of cfg.ppgBandHz, upper edge
    clamped below Nyquist - min(4, 0.95*fs/2) in the project's convention."""
    lo, hi = band_hz
    return lo, min(hi, 0.95 * fs / 2.0)


def bandpass_zero_phase(x: np.ndarray, fs: float, band_hz: tuple[float, float], order: int = 4) -> np.ndarray:
    lo, hi = band_hz_for_fs(band_hz, fs)
    sos = butter(order, [lo, hi], btype="bandpass", fs=fs, output="sos")
    return sosfiltfilt(sos, x)


# ---------------------------------------------------------------------------
# Core algorithm - one-to-one port of estimate_spo2.m
# ---------------------------------------------------------------------------

def estimate_spo2(ir_raw: np.ndarray, red_raw: np.ndarray, fs: float, cfg: Spo2Config) -> pd.DataFrame:
    ir_raw = np.asarray(ir_raw, dtype=float)
    red_raw = np.asarray(red_raw, dtype=float)
    n = min(len(ir_raw), len(red_raw))
    ir_raw, red_raw = ir_raw[:n], red_raw[:n]

    win_samples = round(cfg.window_sec * fs)
    step_samples = round(cfg.step_sec * fs)

    # --- Warm-up skip ---
    n_skip = max(0, round(cfg.warmup_sec * fs))
    if n_skip > 0 and (n - n_skip) < win_samples:
        n_skip = max(0, n - win_samples)
    time_offset = n_skip / fs
    if n_skip > 0:
        ir_raw = ir_raw[n_skip:]
        red_raw = red_raw[n_skip:]
        n = len(ir_raw)

    if n < win_samples:
        return _empty_result(cfg)

    # --- Pulsatile components (DC must come from the RAW signal) ---
    ir_ac = bandpass_zero_phase(detrend(ir_raw), fs, cfg.band_hz, 4)
    red_ac = bandpass_zero_phase(detrend(red_raw), fs, cfg.band_hz, 4)

    n_rows = (n - win_samples) // step_samples + 1
    time_s = np.full(n_rows, np.nan)
    r_arr = np.full(n_rows, np.nan)
    spo2_arr = np.full(n_rows, np.nan)
    pi_ir = np.full(n_rows, np.nan)
    pi_red = np.full(n_rows, np.nan)

    for row in range(n_rows):
        start = row * step_samples
        idx = slice(start, start + win_samples)
        time_s[row] = time_offset + (start + (win_samples - 1) / 2.0) / fs

        dc_ir = ir_raw[idx].mean()
        dc_red = red_raw[idx].mean()
        if dc_ir <= 0 or dc_red <= 0:
            continue

        pi_ir[row] = ir_ac[idx].std(ddof=1) / dc_ir
        pi_red[row] = red_ac[idx].std(ddof=1) / dc_red
        if pi_ir[row] < cfg.min_perfusion or pi_red[row] < cfg.min_perfusion:
            continue

        r_arr[row] = pi_red[row] / pi_ir[row]
        if r_arr[row] < cfg.valid_r_range[0] or r_arr[row] > cfg.valid_r_range[1]:
            continue

        spo2_arr[row] = spo2_curve(r_arr[row], cfg.calibration)

    # --- Optional one-point offset calibration ---
    sp = cfg.single_point
    cal_offset = 0.0
    cal_info = "none (generic published curve, uncalibrated)"

    if sp.enable:
        if sp.ref_spo2 is None:
            print("  WARNING: single-point calibration enabled but ref_spo2 is None. Skipping.")
        else:
            selectable = np.isfinite(r_arr)
            if sp.time_range is not None:
                selectable = selectable & (time_s >= sp.time_range[0]) & (time_s <= sp.time_range[1])
            if not np.any(selectable):
                print("  WARNING: no windows with a usable R in the calibration time range. Skipping.")
            else:
                r_cal = np.nanmedian(r_arr[selectable])
                spo2_at_cal = spo2_curve(r_cal, cfg.calibration)
                cal_offset = sp.ref_spo2 - spo2_at_cal
                spo2_arr = spo2_arr + cal_offset
                cal_info = (
                    f"one-point offset: R_cal = {r_cal:.4f} over {int(selectable.sum())} window(s) -> "
                    f"curve gives {spo2_at_cal:.1f}%, reference {sp.ref_spo2:.1f}%, offset {cal_offset:+.2f}%"
                )

    valid = np.isfinite(spo2_arr) & (spo2_arr >= cfg.valid_range[0]) & (spo2_arr <= cfg.valid_range[1])

    table = pd.DataFrame({
        "time_s": time_s, "R": r_arr, "SpO2_percent": spo2_arr,
        "PI_IR": pi_ir, "PI_RED": pi_red, "valid": valid,
    })
    table.attrs["calibrationOffset"] = cal_offset
    table.attrs["calibrationInfo"] = cal_info
    table.attrs["warmupSec"] = time_offset
    table.attrs["curve"] = cfg.calibration.lower()
    return table


def _empty_result(cfg: Spo2Config) -> pd.DataFrame:
    table = pd.DataFrame(columns=["time_s", "R", "SpO2_percent", "PI_IR", "PI_RED", "valid"])
    table.attrs["calibrationOffset"] = 0.0
    table.attrs["calibrationInfo"] = "none (recording shorter than one window after warm-up skip)"
    table.attrs["warmupSec"] = 0.0
    table.attrs["curve"] = cfg.calibration.lower()
    return table


def print_report(table: pd.DataFrame, cfg: Spo2Config, label: str) -> None:
    n_rows = len(table)
    n_valid = int(table["valid"].sum()) if n_rows else 0
    print(f"\n{'=' * 60}")
    print(f"SpO2 ESTIMATION ({label}) - {cfg.calibration} curve")
    print(f"{'=' * 60}")
    print(f"Warm-up skipped       : {table.attrs.get('warmupSec', 0.0):.1f} s")
    print(f"Calibration           : {table.attrs.get('calibrationInfo')}")
    print(f"Windows analysed      : {n_rows}")
    print(f"Windows accepted      : {n_valid} ({100 * n_valid / max(n_rows, 1):.1f}%)")
    if n_rows:
        print(f"Median perfusion index: IR {100 * table['PI_IR'].median():.4f}%   "
              f"RED {100 * table['PI_RED'].median():.4f}%")
        print(f"Median R              : {table['R'].median():.4f}  (SD {table['R'].std():.4f})")
        if n_valid:
            acc = table[table["valid"]]
            print(f"Accepted SpO2         : median {acc['SpO2_percent'].median():.1f}%  "
                  f"range {acc['SpO2_percent'].min():.1f}-{acc['SpO2_percent'].max():.1f}%")
        else:
            print("Accepted SpO2         : no valid windows")
    print(f"{'=' * 60}\n")


# ---------------------------------------------------------------------------
# sep16 loading - mirrors tools/prepare_datasets.py's repair/drop-row0
# ---------------------------------------------------------------------------

def repair_sep16_timestamps(t: np.ndarray) -> np.ndarray:
    t = t.copy()
    dt = np.diff(t)
    median_dt = np.median(dt)
    bad_gap = np.abs(dt - median_dt) > 10 * median_dt
    for i in range(1, len(t) - 1):
        if bad_gap[i - 1] and bad_gap[i]:
            t[i] = 0.5 * (t[i - 1] + t[i + 1])
    return t


def load_sep16_native_repaired() -> tuple[np.ndarray, np.ndarray, float]:
    """(a) repaired 16.667Hz sep16: 3 corrupted timestamps interpolated,
    row 0 (startup glitch) dropped. No resampling."""
    df = pd.read_csv(SEP16_CAPTURE_CSV)
    df = df.assign(time_s=repair_sep16_timestamps(df["time_s"].to_numpy(dtype=float)))
    df = df.iloc[1:].reset_index(drop=True)
    return df["ir"].to_numpy(dtype=float), df["red"].to_numpy(dtype=float), SEP16_SOURCE_FS_HZ


def load_sep16_25hz_asset() -> tuple[np.ndarray, np.ndarray, float]:
    """(b) the shipped 25Hz asset (hw_sep16.csv) - what the app actually
    plays back. Requires tools/prepare_datasets.py to have been run first."""
    path = ASSETS_DIR / "hw_sep16.csv"
    if not path.exists():
        sys.exit(f"{path} not found - run tools/prepare_datasets.py first")
    df = pd.read_csv(path)
    return df["ir"].to_numpy(dtype=float), df["red"].to_numpy(dtype=float), 25.0


def load_matlab_wrongly_split_segment() -> tuple[np.ndarray, np.ndarray, float]:
    """Reproduces split_recording_sessions.m's "longest clean segment":
    the first 1898 RAW rows (0-based 0..1897) of sep16_capture.csv, BEFORE
    any repair or row-0 drop - this is what the MATLAB tooling actually
    analysed. Used only as a cross-check against the numbers in
    SEP16_CAPTURE_NOTES.txt, not as anything the app ships."""
    df = pd.read_csv(SEP16_CAPTURE_CSV)
    seg = df.iloc[:1898]
    return seg["ir"].to_numpy(dtype=float), seg["red"].to_numpy(dtype=float), SEP16_SOURCE_FS_HZ


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)

    # --- (a) repaired native-rate sep16, default config ---
    ir_a, red_a, fs_a = load_sep16_native_repaired()
    cfg_default = Spo2Config()
    table_a = estimate_spo2(ir_a, red_a, fs_a, cfg_default)
    print_report(table_a, cfg_default, f"sep16 repaired, {fs_a:.4f} Hz")
    table_a.to_csv(GOLDEN_DIR / "spo2_sep16_16667hz.csv", index=False)

    # --- (a) one-point calibration, ref=98 ---
    cfg_cal = Spo2Config(single_point=SinglePointCalibration(enable=True, ref_spo2=98.0))
    table_a_cal = estimate_spo2(ir_a, red_a, fs_a, cfg_cal)
    print_report(table_a_cal, cfg_cal, f"sep16 repaired, {fs_a:.4f} Hz, calibrated ref=98")

    # --- (b) shipped 25Hz asset, default config ---
    ir_b, red_b, fs_b = load_sep16_25hz_asset()
    table_b = estimate_spo2(ir_b, red_b, fs_b, cfg_default)
    print_report(table_b, cfg_default, f"hw_sep16.csv (shipped asset), {fs_b} Hz")
    table_b.to_csv(GOLDEN_DIR / "spo2_sep16_25hz.csv", index=False)

    # --- (b) one-point calibration, ref=98 ---
    table_b_cal = estimate_spo2(ir_b, red_b, fs_b, cfg_cal)
    print_report(table_b_cal, cfg_cal, f"hw_sep16.csv (shipped asset), {fs_b} Hz, calibrated ref=98")

    # --- Cross-check against MATLAB's wrongly-split first segment ---
    ir_c, red_c, fs_c = load_matlab_wrongly_split_segment()
    cfg_warmup30 = Spo2Config(warmup_sec=30.0)
    cfg_warmup0 = Spo2Config(warmup_sec=0.0)
    table_c30 = estimate_spo2(ir_c, red_c, fs_c, cfg_warmup30)
    table_c0 = estimate_spo2(ir_c, red_c, fs_c, cfg_warmup0)
    print_report(table_c30, cfg_warmup30, "MATLAB wrongly-split segment (1898 raw rows), warmup=30s")
    print_report(table_c0, cfg_warmup0, "MATLAB wrongly-split segment (1898 raw rows), warmup=0s")

    print("Cross-check against SEP16_CAPTURE_NOTES.txt / HARDWARE_DATA_NOTES.txt:")
    print(f"  warmup=30s: expected 37 windows / 0 accepted / median R 0.3439 "
          f"-> got {len(table_c30)} / {int(table_c30['valid'].sum())} / {table_c30['R'].median():.4f}")
    print(f"  warmup=0s : expected 52 windows / 5 accepted / median R 0.3414 "
          f"-> got {len(table_c0)} / {int(table_c0['valid'].sum())} / {table_c0['R'].median():.4f}")


if __name__ == "__main__":
    main()
