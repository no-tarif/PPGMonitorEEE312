package com.eee312.ppgmonitor.dataset

import android.content.Context
import org.json.JSONObject

/**
 * Reads assets/datasets/datasets.json (written by tools/prepare_datasets.py)
 * into a list of [DatasetInfo]. Uses org.json (built into the Android SDK)
 * rather than adding a kotlinx-serialization dependency - the manifest is
 * small and flat, not worth a new dependency for.
 */
object DatasetCatalog {
    private const val ASSET_DIR = "datasets"
    private const val MANIFEST_FILE = "datasets.json"

    fun load(context: Context): List<DatasetInfo> {
        val text = context.assets.open("$ASSET_DIR/$MANIFEST_FILE")
            .bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("datasets")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            DatasetInfo(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                subject = o.getString("subject"),
                activity = o.getString("activity"),
                sampleRateHz = o.getDouble("sampleRateHz"),
                durationSec = o.getDouble("durationSec"),
                sampleCount = o.getInt("sampleCount"),
                sourcePlethChannel = if (o.isNull("sourcePlethChannel")) null else o.getString("sourcePlethChannel"),
                referenceHrBpm = if (o.isNull("referenceHrBpm")) null else o.getDouble("referenceHrBpm"),
                hasReferenceHrSeries = o.getBoolean("hasReferenceHrSeries"),
                hasRed = o.optBoolean("hasRed", true),
                hasGreen = o.optBoolean("hasGreen", false),
                wavelengthsNm = o.optJSONObject("wavelengthsNm")?.let { wl ->
                    wl.keys().asSequence().associateWith { wl.getInt(it) }
                },
                originalSampleRateHz = if (o.has("originalSampleRateHz") && !o.isNull("originalSampleRateHz")) {
                    o.getDouble("originalSampleRateHz")
                } else null,
                spo2Eligible = o.optBoolean("spo2Eligible", false),
                preprocessingNotes = if (o.has("preprocessingNotes") && !o.isNull("preprocessingNotes")) {
                    o.getString("preprocessingNotes")
                } else null
            )
        }
    }
}
