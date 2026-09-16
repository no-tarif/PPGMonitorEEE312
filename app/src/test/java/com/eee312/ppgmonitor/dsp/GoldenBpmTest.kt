package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Regression guard for Rule Zero: replays every bundled dataset through
 * the untouched [PpgPipeline] exactly as [CsvDatasetSource] does in
 * "Fast — no delay" mode (no loop, one pass, no inter-sample sleep) and
 * compares the resulting per-sample BPM/SQI series against a checked-in
 * golden file.
 *
 * If a golden file is missing, it is generated on the spot (this is how
 * the baseline was first captured, pre-spo2). Once a golden file exists,
 * this test must match it bit-for-bit — any difference means something
 * in the DSP path changed, which Rule Zero forbids for this project.
 *
 * Reads assets/datasets straight off disk (not through Android's
 * AssetManager/org.json, which aren't available in a plain JVM test) so
 * this needs no Robolectric.
 */
class GoldenBpmTest {

    private data class DatasetRef(val id: String, val csvFile: File)

    private fun findAppDir(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/assets/datasets/datasets.json")
            if (candidate.exists()) return dir
            val appCandidate = File(dir, "app/src/main/assets/datasets/datasets.json")
            if (appCandidate.exists()) return File(dir, "app")
            dir = dir.parentFile ?: return@repeat
        }
        fail("Could not locate app/src/main/assets/datasets/datasets.json from working dir ${File(".").absolutePath}")
        error("unreachable")
    }

    private fun loadDatasetIds(appDir: File): List<DatasetRef> {
        val manifestFile = File(appDir, "src/main/assets/datasets/datasets.json")
        val text = manifestFile.readText()
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.map { id -> DatasetRef(id, File(appDir, "src/main/assets/datasets/$id.csv")) }
    }

    /** Mirrors CsvDatasetSource.parseLine's tolerant "time,ir,red,accX,accY,accZ[,green]"
     * parsing, including its two SpO2-era extensions (optional trailing
     * green field, ignored; empty red field parsed as 0). */
    private fun parseLine(line: String): SensorSample? {
        val f = line.split(',')
        if (f.size != 6 && f.size != 7) return null
        return try {
            val redField = f[2].trim()
            SensorSample(
                timestampMs = 0L,
                ir = f[1].trim().toInt(),
                red = if (redField.isEmpty()) 0 else redField.toInt(),
                accX = f[3].trim().toFloat(),
                accY = f[4].trim().toFloat(),
                accZ = f[5].trim().toFloat()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun runDataset(csvFile: File): String {
        val pipeline = PpgPipeline()
        val sb = StringBuilder()
        sb.append("index,rawPpg,cleanedPpg,newHrBpm,sqi\n")
        csvFile.bufferedReader().use { reader ->
            reader.readLine() // header
            var idx = 0
            while (true) {
                val line = reader.readLine() ?: break
                val sample = parseLine(line) ?: continue
                val out = pipeline.process(sample)
                sb.append(idx).append(',')
                    .append(out.rawPpg).append(',')
                    .append(out.cleanedPpg).append(',')
                    .append(out.newHrBpm?.toString() ?: "").append(',')
                    .append(if (out.sqi.isNaN()) "" else out.sqi.toString())
                    .append('\n')
                idx++
            }
        }
        return sb.toString()
    }

    @Test
    fun everyBundledDatasetMatchesGoldenBpmSqiSeries() {
        val appDir = findAppDir()
        val datasets = loadDatasetIds(appDir)
        check(datasets.isNotEmpty()) { "datasets.json listed no datasets" }

        val goldenDir = File(appDir, "src/test/resources/golden")
        goldenDir.mkdirs()

        val mismatches = mutableListOf<String>()
        val generated = mutableListOf<String>()

        for (ds in datasets) {
            check(ds.csvFile.exists()) { "Missing dataset asset: ${ds.csvFile}" }
            val actual = runDataset(ds.csvFile)
            val goldenFile = File(goldenDir, "${ds.id}.csv")

            if (!goldenFile.exists()) {
                goldenFile.writeText(actual)
                generated += ds.id
                continue
            }

            val expected = goldenFile.readText()
            if (expected != actual) {
                mismatches += ds.id
                val diffFile = File(goldenDir, "${ds.id}.actual.csv")
                diffFile.writeText(actual)
            }
        }

        if (generated.isNotEmpty()) {
            println("Generated new golden files (baseline capture) for: $generated")
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "BPM/SQI golden mismatch for dataset(s): $mismatches — the untouched DSP " +
                    "pipeline produced different output than the checked-in golden file. " +
                    "See <dataset>.actual.csv next to the golden file for the new output. " +
                    "Rule Zero forbids this: BPM/SQI must be bit-for-bit identical."
            )
        }
    }
}
