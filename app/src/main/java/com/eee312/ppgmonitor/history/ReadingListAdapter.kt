package com.eee312.ppgmonitor.history

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.eee312.ppgmonitor.R
import java.text.SimpleDateFormat
import java.util.Locale

class ReadingListAdapter(
    context: Context,
    private val datasetNames: Map<String, String>
) : ArrayAdapter<ReadingEntity>(context, 0) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun submitList(items: List<ReadingEntity>) {
        clear()
        addAll(items)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_reading, parent, false)
        val reading = getItem(position)!!

        view.findViewById<TextView>(R.id.tvName).text = reading.name
        view.findViewById<TextView>(R.id.tvAvgBpm).text =
            reading.avgBpm?.let { "%.0f BPM".format(it) } ?: "-- BPM"
        view.findViewById<TextView>(R.id.tvSubtitle).text =
            "${sourceLabel(reading)} · ${dateFormat.format(reading.startedAtEpochMs)} · ${formatDuration(reading.durationSec)}"

        view.findViewById<TextView>(R.id.tvSpo2Row).text = spo2RowText(reading)

        val chip = view.findViewById<TextView>(R.id.tvQualityChip)
        // The quality chip is the recording-quality index - hidden unless
        // HistoryDisplayConfig.SHOW_ADVANCED_METRICS is switched back on.
        chip.visibility = if (HistoryDisplayConfig.SHOW_ADVANCED_METRICS) View.VISIBLE else View.GONE
        chip.text = qualityChipText(reading.qualityVerdict)
        (chip.background.mutate() as GradientDrawable).setColor(qualityChipColor(reading.qualityVerdict))

        return view
    }

    /** "SpO2 <median>% (or a short reason) · SQI <mean>%" - see
     * Spo2Status's header for what each non-OK status means. */
    private fun spo2RowText(reading: ReadingEntity): String {
        val spo2Part = when (reading.spo2Status) {
            null, Spo2Status.PENDING -> "SpO2 computing…"
            Spo2Status.LEGACY -> "SpO2 legacy"
            Spo2Status.NOT_ELIGIBLE -> "SpO2 n/a"
            Spo2Status.SINGLE_WAVELENGTH -> "SpO2 1λ"
            Spo2Status.TOO_SHORT -> "SpO2 too short"
            Spo2Status.NO_VALID_WINDOWS -> "SpO2 no valid windows"
            Spo2Status.FEW_VALID_WINDOWS -> "SpO2 N/A (<3 valid)"
            Spo2Status.OK -> "SpO2 %.0f%%".format(reading.spo2Median ?: Double.NaN)
        }
        val sqiPart = reading.meanSqi?.let { " · SQI %.0f%%".format(it * 100) } ?: ""
        return spo2Part + sqiPart
    }

    private fun qualityChipText(verdict: String?): String = when (verdict) {
        "GOOD" -> "GOOD"
        "MARGINAL" -> "MARGINAL"
        "NOT_USABLE" -> "NOT USABLE"
        else -> "…"
    }

    private fun qualityChipColor(verdict: String?): Int = when (verdict) {
        "GOOD" -> Color.parseColor("#2E7D32")
        "MARGINAL" -> Color.parseColor("#F9A825")
        "NOT_USABLE" -> Color.parseColor("#C62828")
        else -> Color.parseColor("#9E9E9E")
    }

    private fun sourceLabel(reading: ReadingEntity): String = when (reading.sourceType) {
        SourceType.SIMULATED -> "Simulated"
        SourceType.DATASET -> "Dataset" + (reading.datasetId?.let { id ->
            datasetNames[id]?.let { " — $it" }
        } ?: "")
        SourceType.ESP32_BLE -> "ESP32 (BLE)"
        SourceType.ESP32_USB -> "ESP32 (USB)"
        SourceType.FILE -> reading.sourceLabel ?: "File"
    }

    private fun formatDuration(sec: Double): String {
        val totalSec = sec.toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
