package com.eee312.ppgmonitor.history

import android.content.Context
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

        return view
    }

    private fun sourceLabel(reading: ReadingEntity): String = when (reading.sourceType) {
        SourceType.SIMULATED -> "Simulated"
        SourceType.DATASET -> "Dataset" + (reading.datasetId?.let { id ->
            datasetNames[id]?.let { " — $it" }
        } ?: "")
        SourceType.ESP32_BLE -> "ESP32 (BLE)"
        SourceType.ESP32_USB -> "ESP32 (USB)"
    }

    private fun formatDuration(sec: Double): String {
        val totalSec = sec.toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
