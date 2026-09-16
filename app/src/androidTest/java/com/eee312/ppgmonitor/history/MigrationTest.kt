package com.eee312.ppgmonitor.history

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_1_2 is non-destructive: an existing v1 database (the
 * exact schema this app shipped before SpO2 - see
 * app/schemas/.../1.json, captured from the unmodified pre-Phase-3
 * entities) upgrades to v2 with every pre-existing row intact, and the
 * new columns/table are usable afterwards.
 *
 * This is the Rule Zero guarantee that matters most: the phone's real
 * "Reading 35-43" data must survive this exact migration path.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesExistingReadingsAndSamples() {
        // --- Arrange: a v1 database with one reading and a few samples,
        // exactly as SessionRecorder would have written before SpO2. ---
        var db = helper.createDatabase(TEST_DB, 1)
        db.execSQL(
            "INSERT INTO readings (id, name, sourceType, datasetId, startedAtEpochMs, " +
                "durationSec, sampleRateHz, avgBpm, minBpm, maxBpm, meanSqi, notes) VALUES " +
                "(1, 'Reading 1', 'DATASET', 's11_sit', 1700000000000, 487.2, 25.0, 72.66, 60.0, 90.0, 0.55, NULL)"
        )
        for (i in 0 until 5) {
            db.execSQL(
                "INSERT INTO samples (readingId, tSec, rawPpg, cleanedPpg, accMagnitude, bpm) VALUES " +
                    "(1, $i.0, ${90000 + i}.0, 0.5, 9.8, NULL)"
            )
        }
        db.close()

        // --- Act: migrate to v2 (opens the db via the REAL current Room
        // implementation, running MIGRATION_1_2 - fails the test if the
        // migration is missing, wrong, or leaves a schema mismatch). ---
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
        db.close()

        // --- Assert: reopen through Room itself and check the data + new columns. ---
        val roomDb = androidx.room.Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(MIGRATION_1_2).build()

        try {
            val reading = kotlinx.coroutines.runBlocking { roomDb.readingDao().getById(1) }
            assertTrue("expected the pre-migration reading to survive", reading != null)
            assertEquals("Reading 1", reading!!.name)
            assertEquals(72.66, reading.avgBpm!!, 1e-9)
            // New column, backfilled by the migration for every pre-existing row.
            assertEquals(Spo2Status.LEGACY, reading.spo2Status)
            // New nullable columns default to null, not dropped/garbled data.
            assertEquals(null, reading.spo2Median)

            val samples = kotlinx.coroutines.runBlocking { roomDb.sampleDao().getForReading(1) }
            assertEquals(5, samples.size)
            assertEquals(90000.0, samples[0].rawPpg, 1e-9)
            assertEquals(null, samples[0].irRaw) // legacy sample, new column is null

            // New table is present and usable.
            val windows = kotlinx.coroutines.runBlocking { roomDb.spo2WindowDao().getForReading(1) }
            assertEquals(0, windows.size)
        } finally {
            roomDb.close()
        }
    }
}
