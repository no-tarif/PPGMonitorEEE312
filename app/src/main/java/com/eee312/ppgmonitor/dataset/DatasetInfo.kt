package com.eee312.ppgmonitor.dataset

/**
 * One entry from assets/datasets/datasets.json - mirrors the
 * ManifestEntry dataclass in tools/prepare_datasets.py field-for-field.
 * See that script's header comment for how sourcePlethChannel and
 * referenceHrBpm were derived.
 *
 * The SpO2-era fields below (hasRed onward) are all optional in the JSON
 * with defaults matching the Python dataclass's, so every dataset bundled
 * before SpO2 work started keeps parsing unchanged - see
 * DatasetCatalog.load()'s `opt*` calls.
 */
data class DatasetInfo(
    val id: String,
    val displayName: String,
    val subject: String,
    val activity: String,
    val sampleRateHz: Double,
    val durationSec: Double,
    val sampleCount: Int,
    val sourcePlethChannel: String?,
    val referenceHrBpm: Double?,
    val hasReferenceHrSeries: Boolean,
    val hasRed: Boolean = true,
    val hasGreen: Boolean = false,
    /** e.g. {"ir":880,"red":660,"green":537} - null when the channel(s)
     * weren't picked by real wavelength (see preprocessingNotes). */
    val wavelengthsNm: Map<String, Int>? = null,
    /** Sample rate of the source recording before any resampling this
     * script applied - null when not known/not applicable. */
    val originalSampleRateHz: Double? = null,
    /** true only for recordings with a real, wavelength-selected ir AND
     * red channel - see tools/prepare_datasets.py for why this is false
     * for every PhysioNet/older-hardware dataset (channels picked by SNR,
     * not wavelength) and for single-wavelength captures. */
    val spo2Eligible: Boolean = false,
    val preprocessingNotes: String? = null
)
