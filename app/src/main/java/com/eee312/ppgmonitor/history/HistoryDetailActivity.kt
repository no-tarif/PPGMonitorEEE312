package com.eee312.ppgmonitor.history

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eee312.ppgmonitor.R
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import com.eee312.ppgmonitor.dsp.spo2.Spo2Config
import com.eee312.ppgmonitor.dsp.spo2.Spo2SinglePointCalibration
import com.eee312.ppgmonitor.ui.WaveformView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Shows one saved reading: the full stored cleaned-PPG waveform and
 * BPM-over-time trend (both via WaveformView's static-overview mode -
 * see that class's header), plus summary stats including SpO2 and
 * recording-quality (Phase 3). Options button offers the same
 * rename/delete/export set as before, plus SpO2 calibration. */
class HistoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_READING_ID = "reading_id"
    }

    private lateinit var tvName: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvSpo2: TextView
    private lateinit var tvQuality: TextView
    private lateinit var waveformFull: WaveformView
    private lateinit var waveformBpm: WaveformView
    private lateinit var btnOptions: Button

    private val db by lazy { AppDatabase.get(applicationContext) }
    private var reading: ReadingEntity? = null

    /** User picked a folder via SAF - <name>.csv, <name>_spo2_windows.csv
     * and <name>_summary.txt are all written into it. A single
     * CreateDocument() can't produce three files, hence OpenDocumentTree
     * instead of the old single-file export launcher. */
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val r = reading
        if (treeUri != null && r != null) {
            contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            lifecycleScope.launch { exportAllFiles(r, treeUri) }
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

        // SpO2/quality summary text views aren't in the original layout
        // (Phase 3 predates the Phase 4 dashboard rework) - added
        // programmatically just below tvStats so this phase doesn't need
        // to touch activity_history_detail.xml's structure yet.
        val root = tvStats.parent as LinearLayout
        val insertIndex = root.indexOfChild(tvStats) + 1
        tvSpo2 = TextView(this).also { root.addView(it, insertIndex) }
        tvQuality = TextView(this).also { root.addView(it, insertIndex + 1) }

        val readingId = intent.getLongExtra(EXTRA_READING_ID, -1L)
        if (readingId < 0) {
            finish()
            return
        }

        lifecycleScope.launch {
            var r = db.readingDao().getById(readingId)
            if (r == null) {
                finish()
                return@launch
            }
            // On-demand path for LEGACY readings (or anything else that
            // predates a successful post-process pass): quality only,
            // never SpO2 - see ReadingPostProcessor.computeQualityIfMissing.
            if (r.qualityJson == null) {
                ReadingPostProcessor.computeQualityIfMissing(applicationContext, r)
                r = db.readingDao().getById(readingId) ?: r
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

        tvSpo2.text = spo2SummaryText(r)
        tvQuality.text = "Recording quality: ${r.qualityVerdict ?: "computing..."}"
    }

    private fun spo2SummaryText(r: ReadingEntity): String = when (r.spo2Status) {
        null, Spo2Status.PENDING -> "SpO2: computing..."
        Spo2Status.LEGACY -> "SpO2: legacy (recorded before SpO2 support - not recomputed)"
        Spo2Status.NOT_ELIGIBLE -> "SpO2: not computed for this source"
        Spo2Status.SINGLE_WAVELENGTH -> "SpO2: N/A — single wavelength"
        Spo2Status.TOO_SHORT -> "SpO2: recording too short for one analysis window"
        Spo2Status.NO_VALID_WINDOWS -> {
            "SpO2: no valid windows" +
                (r.rMedian?.let { " (median R %.3f)".format(it) } ?: "")
        }
        Spo2Status.OK -> buildString {
            append("SpO2: ${"%.0f".format(r.spo2Median)}%")
            append(" (${"%.0f".format(r.spo2Min)}-${"%.0f".format(r.spo2Max)}%, ")
            append("${"%.0f".format(r.spo2AcceptedPct)}% accepted)")
            r.rMedian?.let { append(" · R %.3f".format(it)) }
            if (r.spo2CalibrationInfo != null) {
                append("\nOne-point offset calibration — slope unverified. ${r.spo2CalibrationInfo}")
            } else {
                append("\nUncalibrated generic ${r.spo2Curve ?: ""} curve — engineering estimate, not for medical use.")
            }
        }
    }

    private fun showOptionsMenu() {
        val r = reading ?: return
        val menu = PopupMenu(this, btnOptions)
        menu.menu.add("Rename")
        menu.menu.add("Delete")
        menu.menu.add("Export")
        if (r.spo2Status == Spo2Status.OK || r.spo2Status == Spo2Status.NO_VALID_WINDOWS) {
            menu.menu.add("Calibrate against reference oximeter…")
            if (r.spo2CalibrationInfo != null) menu.menu.add("Remove calibration")
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> { showRenameDialog(r); true }
                "Delete" -> { showDeleteConfirm(r); true }
                "Export" -> { exportLauncher.launch(null); true }
                "Calibrate against reference oximeter…" -> { showCalibrateDialog(r); true }
                "Remove calibration" -> { removeCalibration(r); true }
                else -> false
            }
        }
        menu.show()
    }

    /** Off by default, never pre-filled - see Spo2SinglePointCalibration's
     * header on why a one-point calibration must never be silently
     * applied or default to a specific reading. */
    private fun showCalibrateDialog(r: ReadingEntity) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val refInput = EditText(this).apply { hint = "Reference SpO2 %, e.g. 98" }
        val startInput = EditText(this).apply { hint = "Start second (optional)" }
        val endInput = EditText(this).apply { hint = "End second (optional)" }
        container.addView(TextView(this).apply {
            text = "One-point OFFSET calibration only — this fixes the offset to match your " +
                "reference reading, using the published curve's slope, which stays unverified. " +
                "It does not prove the device would detect a real desaturation."
        })
        container.addView(refInput)
        container.addView(startInput)
        container.addView(endInput)

        AlertDialog.Builder(this)
            .setTitle("Calibrate against reference oximeter")
            .setView(container)
            .setPositiveButton("Calibrate") { _, _ ->
                val ref = refInput.text.toString().toDoubleOrNull()
                if (ref == null) {
                    Toast.makeText(this, "Enter a numeric reference SpO2", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val start = startInput.text.toString().toDoubleOrNull()
                val end = endInput.text.toString().toDoubleOrNull()
                val timeRange = if (start != null && end != null) start to end else null
                applyCalibration(r, ref, timeRange)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyCalibration(r: ReadingEntity, refSpo2: Double, timeRange: Pair<Double, Double>?) {
        lifecycleScope.launch {
            val cfg = Spo2Config(singlePoint = Spo2SinglePointCalibration(enable = true, refSpo2 = refSpo2, timeRangeSec = timeRange))
            ReadingPostProcessor.recomputeSpo2(applicationContext, r.id, r.sampleRateHz, cfg)
            reloadAndRebind(r.id)
            Toast.makeText(this@HistoryDetailActivity, "Calibrated (one-point offset, slope unverified)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeCalibration(r: ReadingEntity) {
        lifecycleScope.launch {
            ReadingPostProcessor.recomputeSpo2(applicationContext, r.id, r.sampleRateHz, Spo2Config())
            reloadAndRebind(r.id)
            Toast.makeText(this@HistoryDetailActivity, "Calibration removed", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun reloadAndRebind(readingId: Long) {
        val r = db.readingDao().getById(readingId) ?: return
        reading = r
        bindHeader(r)
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

    /** Writes <name>.csv (existing columns first and in the same order,
     * then irRaw,redRaw,greenRaw appended), <name>_spo2_windows.csv, and
     * <name>_summary.txt into the SAF tree the user picked. */
    private suspend fun exportAllFiles(r: ReadingEntity, treeUri: Uri) {
        val safeName = r.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        withContext(Dispatchers.IO) {
            val samples = db.sampleDao().getForReading(r.id)
            val windows = db.spo2WindowDao().getForReading(r.id)

            writeIntoTree(treeUri, "$safeName.csv", "text/csv") { w ->
                w.write("tSec,rawPpg,cleanedPpg,accMagnitude,bpm,irRaw,redRaw,greenRaw\n")
                for (s in samples) {
                    w.write(
                        "${s.tSec},${s.rawPpg},${s.cleanedPpg},${s.accMagnitude},${s.bpm ?: ""}," +
                            "${s.irRaw ?: ""},${s.redRaw ?: ""},${s.greenRaw ?: ""}\n"
                    )
                }
            }

            writeIntoTree(treeUri, "${safeName}_spo2_windows.csv", "text/csv") { w ->
                w.write("tSec,R,SpO2_percent,PI_IR,PI_RED,valid,rejectReason\n")
                for (win in windows) {
                    w.write("${win.tSec},${win.r},${win.spo2},${win.piIr},${win.piRed},${win.valid},${win.rejectReason}\n")
                }
            }

            writeIntoTree(treeUri, "${safeName}_summary.txt", "text/plain") { w ->
                w.write(buildSummaryText(r, windows.size))
            }
        }
        Toast.makeText(this, "Exported ${r.name}", Toast.LENGTH_SHORT).show()
    }

    private fun writeIntoTree(treeUri: Uri, displayName: String, mimeType: String, body: (java.io.Writer) -> Unit) {
        val docUri = DocumentsContract.createDocument(contentResolver, treeUri, mimeType, displayName)
            ?: return
        contentResolver.openOutputStream(docUri)?.bufferedWriter()?.use(body)
    }

    private fun buildSummaryText(r: ReadingEntity, windowCount: Int): String = buildString {
        appendLine("Reading: ${r.name}")
        appendLine("Duration: ${r.durationSec} s, sample rate ${r.sampleRateHz} Hz")
        appendLine()
        appendLine("--- SpO2 ---")
        appendLine("Status: ${r.spo2Status ?: "unknown"}")
        if (r.spo2Status == Spo2Status.OK || r.spo2Status == Spo2Status.NO_VALID_WINDOWS) {
            appendLine("Windows: $windowCount, accepted ${"%.1f".format(r.spo2AcceptedPct ?: 0.0)}%")
            appendLine("Median R: ${r.rMedian ?: "--"}")
            appendLine("Median PI IR / RED: ${r.piIrMedian ?: "--"} / ${r.piRedMedian ?: "--"}")
            appendLine("Curve: ${r.spo2Curve ?: "--"}")
            appendLine("Calibration: ${r.spo2CalibrationInfo ?: "none (uncalibrated)"}")
            r.spo2Median?.let { appendLine("Accepted SpO2: median %.1f%%, range %.1f-%.1f%%".format(it, r.spo2Min, r.spo2Max)) }
        }
        appendLine()
        appendLine("--- Recording quality ---")
        appendLine("Verdict: ${r.qualityVerdict ?: "not computed"}")
        r.qualityJson?.let { appendLine(it) }
        appendLine()
        appendLine("Engineering estimate from a generic published curve — not for medical use.")
    }
}
