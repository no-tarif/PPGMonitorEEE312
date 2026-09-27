package com.eee312.ppgmonitor.history

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eee312.ppgmonitor.R
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Newest-first list of saved readings. Tap -> detail screen. Long-press
 * -> rename / delete / export, per PopupMenu (no extra icon assets). */
class HistoryActivity : AppCompatActivity() {

    private lateinit var lvReadings: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ReadingListAdapter
    private val db by lazy { AppDatabase.get(applicationContext) }

    private var exportTarget: ReadingEntity? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val reading = exportTarget
        exportTarget = null
        if (uri != null && reading != null) {
            lifecycleScope.launch {
                try {
                    val rows = ReadingExport.writeRawCsv(applicationContext, reading, uri)
                    Toast.makeText(this@HistoryActivity, "Exported ${reading.name}: $rows raw samples", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@HistoryActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        lvReadings = findViewById(R.id.lvReadings)
        tvEmpty = findViewById(R.id.tvEmpty)

        val datasetNames = DatasetCatalog.load(applicationContext).associate { it.id to it.displayName }
        adapter = ReadingListAdapter(this, datasetNames)
        lvReadings.adapter = adapter

        lvReadings.setOnItemClickListener { _, _, position, _ ->
            val reading = adapter.getItem(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, HistoryDetailActivity::class.java)
                    .putExtra(HistoryDetailActivity.EXTRA_READING_ID, reading.id)
            )
        }
        lvReadings.setOnItemLongClickListener { view, _, position, _ ->
            val reading = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            showActionsMenu(view, reading)
            true
        }

        lifecycleScope.launch {
            db.readingDao().observeAll().collect { readings ->
                adapter.submitList(readings)
                tvEmpty.visibility = if (readings.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showActionsMenu(anchor: View, reading: ReadingEntity) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Rename")
        menu.menu.add("Delete")
        menu.menu.add("Export")
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> { showRenameDialog(reading); true }
                "Delete" -> { showDeleteConfirm(reading); true }
                "Export" -> {
                    exportTarget = reading
                    exportLauncher.launch(RawCsvExporter.fileName(reading.name, reading.startedAtEpochMs))
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showRenameDialog(reading: ReadingEntity) {
        val input = EditText(this).apply { setText(reading.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename reading")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch { db.readingDao().rename(reading.id, newName) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirm(reading: ReadingEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${reading.name}?")
            .setMessage("This removes its stored waveform and BPM data. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { db.readingDao().delete(reading) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
