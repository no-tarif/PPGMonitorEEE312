package com.eee312.ppgmonitor.history

/** Where a [ReadingEntity]'s SpO2 summary columns stand. Room stores this
 * as its name() (TEXT), same convention as [SourceType]. */
enum class Spo2Status {
    /** Batch SpO2 pass ran and produced at least one accepted window. */
    OK,
    /** Batch SpO2 pass ran but every window was rejected. */
    NO_VALID_WINDOWS,
    /** Only one wavelength was available (e.g. "Hardware - Running 2"). */
    SINGLE_WAVELENGTH,
    /** Recording was shorter than one window after the warm-up skip. */
    TOO_SHORT,
    /** This source/dataset was never eligible for SpO2 (Simulated, or a
     * dataset with spo2Eligible=false in datasets.json). */
    NOT_ELIGIBLE,
    /** Recorded before SpO2 existed - never recomputed automatically; see
     * SessionRecorder/ReadingPostProcessor's header for the on-demand
     * legacy compute path. */
    LEGACY,
    /** Batch pass has been kicked off but hasn't finished yet. */
    PENDING,
    /** v3 (appended - stored by name): the pass ran but fewer than
     * Spo2Config.minValidWindows (3) windows were valid, so no summary
     * SpO2 is given. */
    FEW_VALID_WINDOWS
}
