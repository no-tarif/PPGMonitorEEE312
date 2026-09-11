package com.eee312.ppgmonitor.dataset

/**
 * One entry from assets/datasets/datasets.json - mirrors the
 * ManifestEntry dataclass in tools/prepare_datasets.py field-for-field.
 * See that script's header comment for how sourcePlethChannel and
 * referenceHrBpm were derived.
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
    val hasReferenceHrSeries: Boolean
)
