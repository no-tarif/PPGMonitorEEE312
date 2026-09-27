package com.eee312.ppgmonitor.history

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eee312.ppgmonitor.R
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import com.eee312.ppgmonitor.dsp.spo2.Spo2Config
import com.eee312.ppgmonitor.dsp.spo2.Spo2SinglePointCalibration
import com.eee312.ppgmonitor.ui.ChannelColors
import com.eee312.ppgmonitor.ui.ChartBand
import com.eee312.ppgmonitor.ui.ChartRefLine
import com.eee312.ppgmonitor.ui.ChartSeries
import com.eee312.ppgmonitor.ui.ChartVBand
import com.eee312.ppgmonitor.ui.LineStyle
import com.eee312.ppgmonitor.ui.TrendChartView
import com.eee312.ppgmonitor.ui.WaveformView
import com.eee312.ppgmonitor.ui.ZoomableWaveformView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Shows one saved reading as a scrollable dashboard: summary stats,
 * the cleaned-PPG waveform (with an optional raw IR/red overlay), BPM+
 * SQI/SpO2/R/perfusion-index trends, and the six-check quality report -
 * all sharing the same tSec time base. Options button offers rename/
 * delete/export plus SpO2 calibration. */
class HistoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_READING_ID = "reading_id"
        private val DEFAULT_WARMUP_SEC = Spo2Config().warmupSec
        private const val WAVEFORM_CHUNK = 20_000
    }

    private lateinit var tvName: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var summaryTable: LinearLayout
    private lateinit var waveformZoom: ZoomableWaveformView
    private lateinit var tvSpo2: TextView
    private lateinit var tvQuality: TextView
    private lateinit var waveformFull: WaveformView
    private lateinit var btnToggleOverlay: ToggleButton
    private lateinit var chartBpmSqi: TrendChartView
    private lateinit var chartSpo2: TrendChartView
    private lateinit var chartR: TrendChartView
    private lateinit var chartPi: TrendChartView
    private lateinit var qualityTable: LinearLayout
    private lateinit var btnOptions: Button

    private val db by lazy { AppDatabase.get(applicationContext) }
    private var reading: ReadingEntity? = null
    private var overlayIr: FloatArray? = null
    private var overlayRed: FloatArray? = null

    /** Export = the raw samples as received, one CSV in the canonical
     * capture layout (see RawCsvExporter), streamed off the main thread. */
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = reading
        if (uri != null && r != null) lifecycleScope.launch { exportRaw(r, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)

        tvName = findViewById(R.id.tvDetailName)
        tvSubtitle = findViewById(R.id.tvDetailSubtitle)
        summaryTable = findViewById(R.id.summaryTable)
        waveformZoom = findViewById(R.id.waveformZoom)
        tvSpo2 = findViewById(R.id.tvSpo2)
        tvQuality = findViewById(R.id.tvQuality)
        waveformFull = findViewById(R.id.waveformFull)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        chartBpmSqi = findViewById(R.id.chartBpmSqi)
        chartSpo2 = findViewById(R.id.chartSpo2)
        chartR = findViewById(R.id.chartR)
        chartPi = findViewById(R.id.chartPi)
        qualityTable = findViewById(R.id.qualityTable)
        btnOptions = findViewById(R.id.btnOptions)
        btnOptions.setOnClickListener { showOptionsMenu() }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            reading?.let { exportLauncher.launch(RawCsvExporter.fileName(it.name, it.startedAtEpochMs)) }
        }
        btnToggleOverlay.isEnabled = false
        applyAdvancedVisibility()
        btnToggleOverlay.setOnCheckedChangeListener { _, checked ->
            val ir = overlayIr
            val red = overlayRed
            waveformFull.setOverlayData(
                if (checked && ir != null && red != null) {
                    listOf(ir to ChannelColors.IR, red to ChannelColors.RED)
                } else {
                    emptyList()
                }
            )
        }

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
            loadWaveform(r)

            val bpmSeries = withContext(Dispatchers.IO) { db.sampleDao().getBpmSeries(readingId) }
            val windows = withContext(Dispatchers.IO) { db.spo2WindowDao().getForReading(readingId) }
            bindBpmChart(bpmSeries)
            bindSpo2Chart(windows)

            if (HistoryDisplayConfig.SHOW_ADVANCED_METRICS) {
                val samples = withContext(Dispatchers.IO) { db.sampleDao().getForReading(readingId) }
                waveformFull.setStaticData(FloatArray(samples.size) { samples[it].cleanedPpg.toFloat() })
                if (samples.isNotEmpty() && samples.all { it.irRaw != null && it.redRaw != null }) {
                    overlayIr = FloatArray(samples.size) { samples[it].irRaw!!.toFloat() }
                    overlayRed = FloatArray(samples.size) { samples[it].redRaw!!.toFloat() }
                    btnToggleOverlay.isEnabled = true
                }
                bindSqiIntoBpmChart(samples)
                bindRChart(windows)
                bindPiChart(windows)
                bindQualityTable(r)
            }
        }
    }

    private fun applyAdvancedVisibility() {
        val v = if (HistoryDisplayConfig.SHOW_ADVANCED_METRICS) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.tvSpo2, R.id.tvQuality, R.id.overlayPanel, R.id.advancedPanel)) {
            findViewById<View>(id).visibility = v
        }
        if (HistoryDisplayConfig.SHOW_ADVANCED_METRICS) {
            findViewById<TextView>(R.id.tvBpmChartLabel).text =
                "BPM over time (blue) with per-window signal quality (purple, 0-1 axis)"
        }
    }

    /** Reads cleanedPpg for the whole reading in id-keyed chunks off the
     * main thread and hands the full array to the zoomable chart. */
    private suspend fun loadWaveform(r: ReadingEntity) {
        val values = withContext(Dispatchers.IO) {
            val n = db.sampleDao().countForReading(r.id)
            val out = FloatArray(n)
            var filled = 0
            var afterId = 0L
            while (filled < n) {
                val chunk = db.sampleDao().getWaveformChunk(r.id, afterId, WAVEFORM_CHUNK)
                if (chunk.isEmpty()) break
                for (p in chunk) { if (filled < n) out[filled++] = p.cleanedPpg.toFloat() }
                afterId = chunk.last().id
            }
            if (filled < n) out.copyOf(filled) else out
        }
        waveformZoom.emptyText = "No samples stored for this reading"
        waveformZoom.setData(values, r.sampleRateHz)
    }

    private fun bindBpmChart(points: List<BpmPoint>) {
        if (points.isEmpty()) return
        chartBpmSqi.setData(
            listOf(
                ChartSeries(
                    FloatArray(points.size) { points[it].tSec.toFloat() },
                    FloatArray(points.size) { points[it].bpm.toFloat() },
                    ChannelColors.BPM, LineStyle.SOLID
                )
            )
        )
    }

    /** Advanced only: the old BPM + per-window SQI overlay. */
    private fun bindSqiIntoBpmChart(samples: List<SampleEntity>) {
        val bpmXs = ArrayList<Float>()
        val bpmYs = ArrayList<Float>()
        val sqiXs = ArrayList<Float>()
        val sqiYs = ArrayList<Float>()
        for (s in samples) {
            s.bpm?.let { bpmXs.add(s.tSec.toFloat()); bpmYs.add(it.toFloat()) }
            s.sqi?.let { sqiXs.add(s.tSec.toFloat()); sqiYs.add(it.toFloat()) }
        }
        if (bpmXs.isEmpty()) return
        chartBpmSqi.setData(
            listOf(
                ChartSeries(bpmXs.toFloatArray(), bpmYs.toFloatArray(), ChannelColors.BPM, LineStyle.SOLID, independentScale = true),
                ChartSeries(sqiXs.toFloatArray(), sqiYs.toFloatArray(), ChannelColors.SQI, LineStyle.DASHED, independentScale = true)
            )
        )
    }

    private fun bindSpo2Chart(windows: List<Spo2WindowEntity>) {
        if (windows.isEmpty()) return
        val finite = windows.filter { it.spo2.isFinite() }
        val xs = FloatArray(finite.size) { finite[it].tSec.toFloat() }
        val ys = FloatArray(finite.size) { finite[it].spo2.toFloat() }
        val filled = BooleanArray(finite.size) { finite[it].valid }
        val warmupEnd = DEFAULT_WARMUP_SEC.toFloat()
        chartSpo2.setData(
            series = listOf(ChartSeries(xs, ys, ChannelColors.SPO2, LineStyle.SOLID, markerFilled = filled)),
            yMin = 70f,
            yMax = 105f,
            vBands = listOf(ChartVBand(0f, warmupEnd)),
            refLines = listOf(ChartRefLine(100f))
        )
    }

    private fun bindRChart(windows: List<Spo2WindowEntity>) {
        if (windows.isEmpty()) return
        val finite = windows.filter { it.r.isFinite() }
        if (finite.isEmpty()) return
        val xs = FloatArray(finite.size) { finite[it].tSec.toFloat() }
        val ys = FloatArray(finite.size) { finite[it].r.toFloat() }
        chartR.setData(
            series = listOf(ChartSeries(xs, ys, ChannelColors.SPO2, LineStyle.SOLID)),
            hBands = listOf(ChartBand(0.4f, 1.0f))
        )
    }

    private fun bindPiChart(windows: List<Spo2WindowEntity>) {
        if (windows.isEmpty()) return
        val finiteIr = windows.filter { it.piIr.isFinite() }
        val finiteRed = windows.filter { it.piRed.isFinite() }
        if (finiteIr.isEmpty() && finiteRed.isEmpty()) return
        chartPi.setData(
            series = listOf(
                ChartSeries(
                    FloatArray(finiteIr.size) { finiteIr[it].tSec.toFloat() },
                    FloatArray(finiteIr.size) { (finiteIr[it].piIr * 100).toFloat() },
                    ChannelColors.IR, LineStyle.SOLID
                ),
                ChartSeries(
                    FloatArray(finiteRed.size) { finiteRed[it].tSec.toFloat() },
                    FloatArray(finiteRed.size) { (finiteRed[it].piRed * 100).toFloat() },
                    ChannelColors.RED, LineStyle.DASHED
                )
            )
        )
    }

    private fun bindQualityTable(r: ReadingEntity) {
        qualityTable.removeAllViews()
        val json = r.qualityJson ?: return
        val checks = try { JSONObject(json).getJSONArray("checks") } catch (e: Exception) { return }
        for (i in 0 until checks.length()) {
            val c = checks.getJSONObject(i)
            val name = c.optString("name")
            val verdict = c.optString("verdict")
            val detail = c.optString("detail")
            val row = TextView(this).apply {
                textSize = 13f
                setPadding(0, 6, 0, 6)
                text = "$name: $detail  [$verdict]"
                setTextColor(
                    when (verdict) {
                        "PASS" -> android.graphics.Color.parseColor("#2E7D32")
                        "WARN" -> android.graphics.Color.parseColor("#F9A825")
                        "FAIL" -> android.graphics.Color.parseColor("#C62828")
                        else -> android.graphics.Color.parseColor("#616161")
                    }
                )
            }
            qualityTable.addView(row)
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

        bindSummary(r, sourceLabel)
        tvSpo2.text = spo2SummaryText(r)
        tvQuality.text = "Recording quality: ${r.qualityVerdict ?: "computing..."}"
    }

    /** Exactly the eight summary fields - nothing else. */
    private fun bindSummary(r: ReadingEntity, sourceLabel: String) {
        val totalSec = r.durationSec.toInt()
        val rows = listOf(
            "Source" to sourceLabel,
            "Duration" to "%d:%02d".format(totalSec / 60, totalSec % 60),
            "Sample rate" to "%.2f Hz".format(r.measuredRateHz ?: r.sampleRateHz),
            "Avg BPM" to fmt(r.avgBpm),
            "Min BPM" to fmt(r.minBpm),
            "Max BPM" to fmt(r.maxBpm),
            "SpO2" to spo2ShortText(r),
            "Signal quality" to (r.meanSqi?.let { "%.0f%%".format(it * 100) } ?: "--")
        )
        summaryTable.removeAllViews()
        for ((label, value) in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 3, 0, 3) }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#616161"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            })
            summaryTable.addView(row)
        }
    }

    /** "97%" or "N/A (short reason)". */
    private fun spo2ShortText(r: ReadingEntity): String = when (r.spo2Status) {
        null, Spo2Status.PENDING -> "computing…"
        Spo2Status.OK -> r.spo2Median?.let {
            "%.0f%%".format(it) + if (r.spo2CalibrationInfo != null) " (one-point calibrated)" else ""
        } ?: "N/A (no valid windows)"
        Spo2Status.LEGACY -> "N/A (recorded before SpO2 support)"
        Spo2Status.NOT_ELIGIBLE -> "N/A (not computed for this source)"
        Spo2Status.SINGLE_WAVELENGTH -> "N/A (no red channel)"
        Spo2Status.TOO_SHORT -> "N/A (recording too short)"
        Spo2Status.NO_VALID_WINDOWS -> "N/A (no valid windows)"
    }

    private fun fmt(v: Double?) = v?.let { "%.0f".format(it) } ?: "--"

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
            r.rMedian?.let { append(" · median R %.3f".format(it)) }
            if (r.piIrMedian != null && r.piRedMedian != null && r.piRedMedian > 0) {
                append(" · PI IR %.3f%% / RED %.3f%% (ratio %.1fx)".format(
                    100 * r.piIrMedian, 100 * r.piRedMedian, r.piIrMedian / r.piRedMedian
                ))
            }
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
        if (r.spo2Status == Spo2Status.OK || r.spo2Status == Spo2Status.NO_VALID_WINDOWS) {
            menu.menu.add("Calibrate against reference oximeter…")
            if (r.spo2CalibrationInfo != null) menu.menu.add("Remove calibration")
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> { showRenameDialog(r); true }
                "Delete" -> { showDeleteConfirm(r); true }
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
        val windows = withContext(Dispatchers.IO) { db.spo2WindowDao().getForReading(readingId) }
        bindSpo2Chart(windows)
        if (HistoryDisplayConfig.SHOW_ADVANCED_METRICS) {
            bindRChart(windows)
            bindPiChart(windows)
        }
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

    private suspend fun exportRaw(r: ReadingEntity, uri: Uri) {
        try {
            val rows = ReadingExport.writeRawCsv(applicationContext, r, uri)
            Toast.makeText(this, "Exported ${r.name}: $rows raw samples", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
