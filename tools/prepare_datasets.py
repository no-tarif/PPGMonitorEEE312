"""
prepare_datasets.py - builds the bundled dataset assets for the "Dataset"
SensorSource playback path (PpgMonitor Phase 1/2).

WHAT THIS DOES
---------------
1. PhysioNet Pulse Transit Time PPG records (S11, S12 x {sit, walk, run}),
   500 Hz / ~243k rows / ~40 MB each as exported to CSV, are:
     - anti-alias decimated 500 Hz -> 25 Hz using scipy.signal.decimate
       with an FIR filter (NOT naive every-20th-row subsampling), applied
       in two stages (x5 then x4) per scipy's own guidance for large
       decimation factors.
     - reduced to the app's existing on-disk format (see walk_clean.csv):
           time,ir,red,accX,accY,accZ
       with `time` rebased to seconds-from-zero (the source `time` column
       is an absolute datetime string and is dropped).
     - two of the six `pleth_1..pleth_6` channels are chosen per record
       and mapped onto `ir` / `red`. Channels are ranked by in-band SNR
       (power in the 0.5-4 Hz pulse band, Welch PSD at the native 500 Hz,
       versus power outside that band) computed on the FULL-RATE signal
       before decimation. The two highest-SNR channels are used - higher
       SNR -> `ir` (matches PpgPipeline's HR path, which reads `ir`),
       second-highest -> `red`. This mirrors select_best_ppg_channel.m's
       intent from the MATLAB project, just applied to 6 candidate
       wavelengths instead of 2. The exact channel picked per record,
       and its SNR, is printed when this script runs and written into
       datasets.json's `sourcePlethChannel` field - cite that field in
       the report.
   The full-rate `peaks` column (0/1 ECG R-peak marker - ground truth)
   is used BEFORE decimation to build a reference heart-rate series from
   R-R intervals: bpm = 60 / (t[i+1] - t[i]) for consecutive peaks,
   filtered to the physiologically valid 30-240 BPM range (same range
   HeartRateEstimator.kt uses), timestamped at the later peak of each
   pair, in the same seconds-from-zero base as the decimated CSV. This
   is written as <id>_ref_hr.csv (time,bpm) and its mean becomes
   datasets.json's `referenceHrBpm`.

2. The two existing hardware captures (walk_clean.csv, class_1_clean.csv)
   are already 25 Hz in the right column format - they are just copied
   in and given manifest entries (id "hw_walk" / "hw_class1"). They have
   no ECG ground truth, so hasReferenceHrSeries is false for both.

3. Writes app/src/main/assets/datasets/datasets.json, the manifest the
   app's CsvDatasetSource / dataset picker reads: for each dataset, id,
   displayName, subject, activity, sampleRateHz, durationSec, sampleCount,
   sourcePlethChannel, referenceHrBpm, hasReferenceHrSeries.

RE-RUNNING
----------
Re-run any time the source data changes:

    python tools/prepare_datasets.py

Source paths are hardcoded below (this machine's course-project folder,
outside the repo) - edit DATA_SETS_DIR / HARDWARE_* if that moves.

Requires: pandas, numpy, scipy (pip install pandas numpy scipy).
"""

from __future__ import annotations

import json
import shutil
import sys
from dataclasses import dataclass, asdict, field
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.signal import decimate, resample_poly, welch

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "datasets"

PROJECT_DIR = Path(r"E:\Study Materials\3-1\312\Project")
DATA_SETS_DIR = PROJECT_DIR / "Data Sets"
HARDWARE_WALK_CSV = PROJECT_DIR / "walk_clean.csv"
HARDWARE_CLASS1_CSV = PROJECT_DIR / "class_1_clean.csv"
SEP16_CAPTURE_CSV = PROJECT_DIR / "sep16_capture.csv"
RUNNING2_CSV = PROJECT_DIR / "Running_2.csv"

SOURCE_FS_HZ = 500.0
TARGET_FS_HZ = 25.0
DECIMATE_STAGES = (5, 4)  # 5 * 4 = 20 = 500/25, split per scipy's large-factor guidance

# sep16_capture.csv's sample interval is a constant 0.06s -> exactly 50/3 Hz.
# resample_poly(x, 3, 2) turns that into exactly 25 Hz (50/3 * 3/2 = 25),
# no fractional-rate approximation involved.
SEP16_SOURCE_FS_HZ = 1.0 / 0.06
SEP16_RESAMPLE_UP, SEP16_RESAMPLE_DOWN = 3, 2

PULSE_BAND_HZ = (0.5, 4.0)
HR_RANGE_BPM = (30.0, 240.0)  # matches HeartRateEstimator.kt's hrRangeBpm

PLETH_COLUMNS = [f"pleth_{i}" for i in range(1, 7)]

RECORDS = [
    ("S11", "sit"), ("S11", "walk"), ("S11", "run"),
    ("S12", "sit"), ("S12", "walk"), ("S12", "run"),
]


@dataclass
class ManifestEntry:
    id: str
    displayName: str
    subject: str
    activity: str
    sampleRateHz: float
    durationSec: float
    sampleCount: int
    sourcePlethChannel: str | None
    referenceHrBpm: float | None
    hasReferenceHrSeries: bool
    # --- SpO2-era fields (all optional - DatasetCatalog.kt defaults these
    # for any manifest entry that omits them, so old entries keep parsing) ---
    hasRed: bool = True
    hasGreen: bool = False
    wavelengthsNm: dict[str, int] | None = None
    originalSampleRateHz: float | None = None
    spo2Eligible: bool = False
    preprocessingNotes: str | None = None


# ---------------------------------------------------------------------------
# .hea parsing (best-effort - S12 has no .hea files at all)
# ---------------------------------------------------------------------------

def read_hea_activity(hea_path: Path) -> str | None:
    if not hea_path.exists():
        return None
    for line in hea_path.read_text().splitlines():
        if line.startswith("#") and "<activity>:" in line:
            # "# <filename>: s11_sit <activity>: sit <gender>: ..."
            after = line.split("<activity>:", 1)[1].strip()
            return after.split()[0]
    return None


# ---------------------------------------------------------------------------
# Channel selection
# ---------------------------------------------------------------------------

def band_snr(x: np.ndarray, fs: float, band: tuple[float, float]) -> float:
    """Welch-PSD SNR: power inside `band` versus power outside it."""
    freqs, psd = welch(x, fs=fs, nperseg=min(4096, len(x)))
    in_band = (freqs >= band[0]) & (freqs <= band[1])
    p_in = psd[in_band].sum()
    p_out = psd[~in_band].sum()
    return float(p_in / p_out) if p_out > 0 else float("inf")


def select_ir_red(df: pd.DataFrame) -> tuple[str, str, dict[str, float]]:
    snrs = {col: band_snr(df[col].to_numpy(dtype=float), SOURCE_FS_HZ, PULSE_BAND_HZ)
            for col in PLETH_COLUMNS}
    ranked = sorted(snrs.items(), key=lambda kv: kv[1], reverse=True)
    ir_col, red_col = ranked[0][0], ranked[1][0]
    return ir_col, red_col, snrs


# ---------------------------------------------------------------------------
# Decimation
# ---------------------------------------------------------------------------

def decimate_to_25hz(x: np.ndarray) -> np.ndarray:
    y = x.astype(float)
    for q in DECIMATE_STAGES:
        y = decimate(y, q, ftype="fir", zero_phase=True)
    return y


# ---------------------------------------------------------------------------
# Reference HR from the full-rate `peaks` column
# ---------------------------------------------------------------------------

def reference_hr_series(df: pd.DataFrame, fs: float) -> pd.DataFrame:
    peak_idx = np.flatnonzero(df["peaks"].to_numpy() != 0)
    if len(peak_idx) < 2:
        return pd.DataFrame(columns=["time", "bpm"])

    peak_times = peak_idx / fs
    rr = np.diff(peak_times)
    bpm = 60.0 / rr
    t = peak_times[1:]  # timestamp each estimate at the later peak of the pair

    valid = (bpm >= HR_RANGE_BPM[0]) & (bpm <= HR_RANGE_BPM[1])
    return pd.DataFrame({"time": t[valid], "bpm": bpm[valid]})


# ---------------------------------------------------------------------------
# Per-record processing
# ---------------------------------------------------------------------------

def process_physionet_record(subject: str, activity: str) -> ManifestEntry:
    subj_dir = DATA_SETS_DIR / subject
    stem = f"{subject.lower()}_{activity}"
    csv_path = subj_dir / f"{stem}.csv"
    hea_path = subj_dir / f"{stem}.hea"

    print(f"\n[{stem}] reading {csv_path.name} ...")
    df = pd.read_csv(csv_path)

    hea_activity = read_hea_activity(hea_path)
    if hea_activity and hea_activity != activity:
        print(f"  WARNING: .hea activity '{hea_activity}' != filename activity '{activity}'")

    ir_col, red_col, snrs = select_ir_red(df)
    print(f"  pleth channel SNRs (0.5-4Hz in-band / out-of-band): "
          + ", ".join(f"{c}={v:.2f}" for c, v in snrs.items()))
    print(f"  -> ir  = {ir_col} (SNR {snrs[ir_col]:.2f})")
    print(f"  -> red = {red_col} (SNR {snrs[red_col]:.2f})")

    ir_25 = decimate_to_25hz(df[ir_col].to_numpy())
    red_25 = decimate_to_25hz(df[red_col].to_numpy())
    accx_25 = decimate_to_25hz(df["a_x"].to_numpy())
    accy_25 = decimate_to_25hz(df["a_y"].to_numpy())
    accz_25 = decimate_to_25hz(df["a_z"].to_numpy())

    n = min(len(ir_25), len(red_25), len(accx_25), len(accy_25), len(accz_25))
    time_col = np.arange(n) / TARGET_FS_HZ

    out = pd.DataFrame({
        "time": np.round(time_col, 3),
        "ir": np.clip(np.round(ir_25[:n]), 0, None).astype(np.int64),
        "red": np.clip(np.round(red_25[:n]), 0, None).astype(np.int64),
        "accX": np.round(accx_25[:n], 4),
        "accY": np.round(accy_25[:n], 4),
        "accZ": np.round(accz_25[:n], 4),
    })
    out_path = OUT_DIR / f"{stem}.csv"
    out.to_csv(out_path, index=False)

    ref = reference_hr_series(df, SOURCE_FS_HZ)
    has_ref = len(ref) > 0
    mean_bpm = float(ref["bpm"].mean()) if has_ref else None
    if has_ref:
        ref_out = ref.copy()
        ref_out["time"] = np.round(ref_out["time"], 3)
        ref_out["bpm"] = np.round(ref_out["bpm"], 2)
        ref_out.to_csv(OUT_DIR / f"{stem}_ref_hr.csv", index=False)
        print(f"  reference HR: mean {mean_bpm:.1f} BPM over {len(ref)} R-R intervals")
    else:
        print("  WARNING: no usable R-peaks found, no reference HR series written")

    print(f"  wrote {out_path.name}: {n} samples, {n / TARGET_FS_HZ:.1f} s, "
          f"{out_path.stat().st_size / 1024:.0f} KB")

    return ManifestEntry(
        id=stem,
        displayName=f"{subject} \u2014 {activity.capitalize()}",
        subject=subject,
        activity=activity,
        sampleRateHz=TARGET_FS_HZ,
        durationSec=round(n / TARGET_FS_HZ, 1),
        sampleCount=n,
        sourcePlethChannel=f"ir<-{ir_col} (SNR {snrs[ir_col]:.2f}), red<-{red_col} (SNR {snrs[red_col]:.2f})",
        referenceHrBpm=round(mean_bpm, 1) if mean_bpm is not None else None,
        hasReferenceHrSeries=has_ref,
        hasRed=True,
        hasGreen=False,
        wavelengthsNm=None,
        originalSampleRateHz=SOURCE_FS_HZ,
        spo2Eligible=False,
        preprocessingNotes=(
            "ir/red are the two pleth_* channels with the highest in-band SNR, "
            "not real 660/880nm wavelengths - not eligible for SpO2 (see "
            "select_ir_red() in this script)."
        ),
    )


def copy_hardware_record(src: Path, dataset_id: str, display_name: str) -> ManifestEntry:
    print(f"\n[{dataset_id}] copying {src.name} (already 25 Hz, no conversion needed)")
    df = pd.read_csv(src)
    dst = OUT_DIR / f"{dataset_id}.csv"
    shutil.copyfile(src, dst)
    n = len(df)
    print(f"  wrote {dst.name}: {n} samples, {n / TARGET_FS_HZ:.1f} s, "
          f"{dst.stat().st_size / 1024:.0f} KB")
    return ManifestEntry(
        id=dataset_id,
        displayName=display_name,
        subject="hardware",
        activity="unknown",
        sampleRateHz=TARGET_FS_HZ,
        durationSec=round(n / TARGET_FS_HZ, 1),
        sampleCount=n,
        sourcePlethChannel=None,
        referenceHrBpm=None,
        hasReferenceHrSeries=False,
        hasRed=True,
        hasGreen=False,
        wavelengthsNm=None,
        originalSampleRateHz=None,
        spo2Eligible=False,
        preprocessingNotes=(
            "ir/red wavelengths were never recorded for this early capture - "
            "not eligible for SpO2."
        ),
    )


# ---------------------------------------------------------------------------
# sep16_capture.csv (ESP32 + MAX30105, IR 880nm / red 660nm / green 537nm)
# ---------------------------------------------------------------------------

def repair_sep16_timestamps(t: np.ndarray) -> np.ndarray:
    """Fixes isolated single-row timestamp glitches by linear interpolation
    from their immediate neighbours, WITHOUT splitting the recording.

    sep16_capture.csv has exactly three such rows (0-based 1898, 2066, 2253)
    where a single sample's timestamp is garbage (e.g. 14.445 sandwiched
    between 114.385 and 114.505) - a logging hiccup, not a session boundary.
    The MATLAB tooling's split_recording_sessions.m treats each of these as
    a "session break" and analysed only the longest surviving fragment,
    discarding ~45% of one continuous 206.8s recording. Detected generically
    here (any single-row spike where both neighbouring gaps are anomalous)
    rather than hardcoded indices, so this still works if the capture is
    re-recorded and the glitch count/position changes.
    """
    t = t.copy()
    dt = np.diff(t)
    median_dt = np.median(dt)
    bad_gap = np.abs(dt - median_dt) > 10 * median_dt  # >10x expected spacing
    # A row i (1 <= i <= len(t)-2) is an isolated glitch if BOTH the gap
    # before it and the gap after it are anomalous, but fixing t[i] alone
    # (not its neighbours) would resolve both.
    for i in range(1, len(t) - 1):
        if bad_gap[i - 1] and bad_gap[i]:
            t[i] = 0.5 * (t[i - 1] + t[i + 1])
    return t


def resample_poly_line(x: np.ndarray) -> np.ndarray:
    """resample_poly with padtype='line' - matters here: the default zero
    padding collapses the DC level of the last analysis window and made R
    jump to ~0.98 in early testing. 'line' extrapolates the boundary
    linearly instead, which keeps the DC/perfusion-index statistics SpO2
    depends on intact at the edges."""
    return resample_poly(x, SEP16_RESAMPLE_UP, SEP16_RESAMPLE_DOWN, padtype="line")


def process_sep16() -> ManifestEntry:
    print(f"\n[hw_sep16] reading {SEP16_CAPTURE_CSV.name} ...")
    df = pd.read_csv(SEP16_CAPTURE_CSV)

    t = repair_sep16_timestamps(df["time_s"].to_numpy(dtype=float))
    df = df.assign(time_s=t)

    # Row 0 is a startup glitch (ir ~82.7k while every following sample is
    # ~115k) - drop it before resampling so it doesn't pollute the FIR
    # anti-alias filter's initial conditions.
    df = df.iloc[1:].reset_index(drop=True)

    n_in = len(df)
    print(f"  repaired 3 corrupted timestamps, dropped row 0 -> {n_in} samples "
          f"at {SEP16_SOURCE_FS_HZ:.4f} Hz ({n_in / SEP16_SOURCE_FS_HZ:.1f} s)")

    ir_25 = resample_poly_line(df["ir"].to_numpy(dtype=float))
    red_25 = resample_poly_line(df["red"].to_numpy(dtype=float))
    green_25 = resample_poly_line(df["green"].to_numpy(dtype=float))
    accx_25 = resample_poly_line(df["accX"].to_numpy(dtype=float))
    accy_25 = resample_poly_line(df["accY"].to_numpy(dtype=float))
    accz_25 = resample_poly_line(df["accZ"].to_numpy(dtype=float))

    n = len(ir_25)
    assert n == len(red_25) == len(green_25) == len(accx_25) == len(accy_25) == len(accz_25)
    time_col = np.arange(n) / TARGET_FS_HZ

    out = pd.DataFrame({
        "time": np.round(time_col, 3),
        "ir": np.clip(np.round(ir_25), 0, None).astype(np.int64),
        "red": np.clip(np.round(red_25), 0, None).astype(np.int64),
        "accX": np.round(accx_25, 4),
        "accY": np.round(accy_25, 4),
        "accZ": np.round(accz_25, 4),
        "green": np.clip(np.round(green_25), 0, None).astype(np.int64),
    })
    out_path = OUT_DIR / "hw_sep16.csv"
    out.to_csv(out_path, index=False)
    print(f"  resampled {SEP16_SOURCE_FS_HZ:.4f} Hz -> {TARGET_FS_HZ} Hz "
          f"(resample_poly {SEP16_RESAMPLE_UP}/{SEP16_RESAMPLE_DOWN}, padtype=line): "
          f"{n_in} -> {n} samples")
    print(f"  wrote {out_path.name}: {n} samples, {n / TARGET_FS_HZ:.1f} s, "
          f"{out_path.stat().st_size / 1024:.0f} KB")

    return ManifestEntry(
        id="hw_sep16",
        displayName="Hardware — Sep 16 (IR+Red)",
        subject="hardware",
        activity="unknown",
        sampleRateHz=TARGET_FS_HZ,
        durationSec=round(n / TARGET_FS_HZ, 1),
        sampleCount=n,
        sourcePlethChannel=None,
        referenceHrBpm=None,
        hasReferenceHrSeries=False,
        hasRed=True,
        hasGreen=True,
        wavelengthsNm={"ir": 880, "red": 660, "green": 537},
        originalSampleRateHz=round(SEP16_SOURCE_FS_HZ, 4),
        spo2Eligible=True,
        preprocessingNotes=(
            "3 single-row corrupted timestamps (0-based rows 1898, 2066, 2253 "
            "in the source file) repaired by linear interpolation from their "
            "neighbours - this is one continuous 206.8s recording, NOT several "
            "concatenated sessions. Row 0 (startup glitch, ir~82.7k vs ~115k "
            "everywhere else) dropped. Resampled 16.667Hz -> 25Hz via "
            "scipy.signal.resample_poly(x, 3, 2, padtype='line'); padtype='line' "
            "matters - the default zero padding collapses the last window's DC "
            "and made R jump to ~0.98. Green LED is effectively off (0-134 "
            "counts) - column kept but not used for anything. Red perfusion "
            "index is ~1/3 of IR (median PI_IR ~0.20%, PI_RED ~0.073%): most "
            "SpO2 windows read above the generic curve's 100% ceiling and are "
            "rejected - a real hardware finding (red LED under-driven), not a "
            "processing bug."
        ),
    )


# ---------------------------------------------------------------------------
# Running_2.csv (this app's own History CSV export - single wavelength)
# ---------------------------------------------------------------------------

def process_running2() -> ManifestEntry:
    print(f"\n[hw_running2] reading {RUNNING2_CSV.name} ...")
    df = pd.read_csv(RUNNING2_CSV)

    # rawPpg is PpgPipeline.Output.rawPpg, which is always sample.ir - see
    # ppgChannelSelector in PpgPipeline.kt. This export has no red channel
    # at all (single wavelength) and accMagnitude is a scalar (sqrt of the
    # three axes), not per-axis data, so accX/Y/Z are written as 0 - this
    # DISCARDS the real accelerometer waveform. That also means the LMS
    # motion-artifact filter will have no real reference during playback of
    # this dataset (it already had none during the original capture either -
    # see the Phase 0 report on why accMagnitude was 0 throughout).
    n = len(df)
    out = pd.DataFrame({
        "time": df["tSec"].round(3),
        "ir": np.clip(df["rawPpg"].round(), 0, None).astype(np.int64),
        "red": ["" for _ in range(n)],
        "accX": np.zeros(n),
        "accY": np.zeros(n),
        "accZ": np.zeros(n),
    })
    out_path = OUT_DIR / "hw_running2.csv"
    out.to_csv(out_path, index=False)
    print(f"  wrote {out_path.name}: {n} samples, {n / TARGET_FS_HZ:.1f} s, "
          f"{out_path.stat().st_size / 1024:.0f} KB (red empty, accX/Y/Z=0 - see manifest notes)")

    return ManifestEntry(
        id="hw_running2",
        displayName="Hardware — Running 2 (single λ)",
        subject="hardware",
        activity="run",
        sampleRateHz=TARGET_FS_HZ,
        durationSec=round(n / TARGET_FS_HZ, 1),
        sampleCount=n,
        sourcePlethChannel=None,
        referenceHrBpm=None,
        hasReferenceHrSeries=False,
        hasRed=False,
        hasGreen=False,
        wavelengthsNm={"ir": 880},
        originalSampleRateHz=TARGET_FS_HZ,
        spo2Eligible=False,
        preprocessingNotes=(
            "Exported from this app's own History (single-wavelength capture, "
            "ir only - SpO2 needs red AND ir from the same instant, see "
            "estimate_spo2.m). accMagnitude in the source export is a scalar "
            "sqrt(x^2+y^2+z^2), not per-axis data, so accX/Y/Z are written as "
            "0 here rather than fabricated - the LMS motion-artifact filter "
            "gets no real reference on playback of this dataset (accMagnitude "
            "was already 0 throughout the original capture, i.e. the "
            "accelerometer likely wasn't wired/initialised on the firmware at "
            "capture time - see the Phase 0 report). bpm/cleanedPpg from the "
            "original export are not reused; the app recomputes them fresh "
            "from ir on playback."
        ),
    )


def main() -> None:
    if not DATA_SETS_DIR.exists():
        sys.exit(f"Data Sets folder not found: {DATA_SETS_DIR}")
    if not SEP16_CAPTURE_CSV.exists():
        sys.exit(f"sep16 capture not found: {SEP16_CAPTURE_CSV}")
    if not RUNNING2_CSV.exists():
        sys.exit(f"Running_2 capture not found: {RUNNING2_CSV}")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for old in OUT_DIR.glob("*"):
        old.unlink()

    manifest: list[ManifestEntry] = []
    for subject, activity in RECORDS:
        manifest.append(process_physionet_record(subject, activity))

    manifest.append(copy_hardware_record(HARDWARE_WALK_CSV, "hw_walk", "Hardware \u2014 Walk"))
    manifest.append(copy_hardware_record(HARDWARE_CLASS1_CSV, "hw_class1", "Hardware \u2014 Class 1"))
    manifest.append(process_sep16())
    manifest.append(process_running2())

    manifest_path = OUT_DIR / "datasets.json"
    manifest_path.write_text(
        json.dumps({"datasets": [asdict(m) for m in manifest]}, indent=2),
        encoding="utf-8",
    )

    total_bytes = sum(f.stat().st_size for f in OUT_DIR.glob("*") if f.is_file())
    print(f"\n{'=' * 60}")
    print(f"Wrote {len(manifest)} datasets + manifest to {OUT_DIR}")
    print(f"Total assets/datasets size: {total_bytes / 1024 / 1024:.2f} MB")
    for f in sorted(OUT_DIR.glob("*"), key=lambda p: -p.stat().st_size):
        print(f"  {f.name:30s} {f.stat().st_size / 1024:8.1f} KB")


if __name__ == "__main__":
    main()
