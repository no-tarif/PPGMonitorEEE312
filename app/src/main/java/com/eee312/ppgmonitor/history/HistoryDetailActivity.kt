package com.eee312.ppgmonitor.history

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eee312.ppgmonitor.R
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import com.eee312.ppgmonitor.ui.WaveformView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Shows one saved reading: the full stored cleaned-PPG waveform and
 * BPM-over-time trend (both via WaveformView's static-overview mode -
 * see that class's header), plus summary stats. Options button offers
 * the same rename/delete/export set as the list's long-press menu. */
class HistoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_READING_ID = "reading_id"
    }

    private lateinit var tvName: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStats: TextView
    private lateinit var waveformFull: WaveformView
    private lateinit var waveformBpm: WaveformView
    private lateinit var btnOptions: Button

    private val db by lazy { AppDatabase.get(applicationContext) }
    private var reading: ReadingEntity? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = reading
        if (uri != null && r != null) {
            lifecycleScope.launch { writeCsv(r, uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)

        tvName = findViewById(R.id.tvDetailName)
        tvSubtitle = findViewById(R.id.tvDetailSubtitle)
        tvStats = findViewById(R.id.tvStats)
        waveformFull = findViewById(R.id.waveformFull)
        waveformBpm = findViewById(R.id.waveformBpm)
        btnOptions = findViewById(R.id.btnOptions)
        btnOptions.setOnClickListener { showOptionsMenu() }

        val readingId = intent.getLongExtra(EXTRA_READING_ID, -1L)
        if (readingId < 0) {
            finish()
            return
        }

        lifecycleScope.launch {
            val r = db.readingDao().getById(readingId)
            if (r == null) {
                finish()
                return@launch
            }
            reading = r
            bindHeader(r)

            val samples = withContext(Dispatchers.IO) { db.sampleDao().getForReading(readingId) }
            waveformFull.setStaticData(FloatArray(samples.size) { samples[it].cleanedPpg.toFloat() })

            val bpmPoints = samples.mapNotNull { it.bpm?.toFloat() }
            if (bpmPoints.isNotEmpty()) waveformBpm.setStaticData(bpmPoints.toFloatArray())
        }
    }

    private fun bindHeader(r: ReadingEntity) {
        tvName.text = r.name
        val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        val sourceLabel = when (r.sourceType) {
            SourceType.SIMULATED -> "Simulated"
            SourceType.DATASET -> "Dataset" + (r.datasetId?.let { id ->
                DatasetCatalog.load(applicationContext).find { it.id == id }?.displayName?.let { " — $it" }
            } ?: "")
            SourceType.ESP32_BLE -> "ESP32 (BLE)"
            SourceType.ESP32_USB -> "ESP32 (USB)"
        }
        tvSubtitle.text = "$sourceLabel · ${dateFormat.format(r.startedAtEpochMs)}"

        val totalSec = r.durationSec.toInt()
        tvStats.text = buildString {
            append("Duration: %d:%02d\n".format(totalSec / 60, totalSec % 60))
            append("Avg BPM: ${r.avgBpm?.let { "%.0f".format(it) } ?: "--"}\n")
            append("Min / Max BPM: ${r.minBpm?.let { "%.0f".format(it) } ?: "--"} / ${r.maxBpm?.let { "%.0f".format(it) } ?: "--"}\n")
            append("Mean signal quality: ${r.meanSqi?.let { "%.0f%%".format(it * 100) } ?: "--"}\n")
            append("Sample rate: ${r.sampleRateHz.toInt()} Hz")
        }
    }

    private fun showOptionsMenu() {
        val r = reading ?: return
        val menu = PopupMenu(this, btnOptions)
        menu.menu.add("Rename")
        menu.menu.add("Delete")
        menu.menu.add("Export as CSV")
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> { showRenameDialog(r); true }
                "Delete" -> { showDeleteConfirm(r); true }
                "Export as CSV" -> {
                    exportLauncher.launch("${r.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.csv")
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showRenameDialog(r: ReadingEntity) {
        val input = EditText(this).apply { setText(r.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename reading")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.readingDao().rename(r.id, newName)
                        reading = r.copy(name = newName)
                        tvName.text = newName
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirm(r: ReadingEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${r.name}?")
            .setMessage("This removes its stored waveform and BPM data. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    db.readingDao().delete(r)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun writeCsv(r: ReadingEntity, uri: Uri) {
        val samples = withContext(Dispatchers.IO) { db.sampleDao().getForReading(r.id) }
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                w.write("tSec,rawPpg,cleanedPpg,accMagnitude,bpm\n")
                for (s in samples) {
                    w.write("${s.tSec},${s.rawPpg},${s.cleanedPpg},${s.accMagnitude},${s.bpm ?: ""}\n")
                }
            }
        }
        Toast.makeText(this, "Exported ${r.name}", Toast.LENGTH_SHORT).show()
    }
}
