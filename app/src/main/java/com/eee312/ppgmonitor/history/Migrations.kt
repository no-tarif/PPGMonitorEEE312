package com.eee312.ppgmonitor.history

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: adds SpO2 + recording-quality support. ALTER TABLE ADD COLUMN
 * / CREATE TABLE only, per Rule Zero - no existing column is dropped,
 * renamed, or retyped, so every reading and sample recorded before this
 * migration survives untouched.
 *
 * Every existing reading is stamped spo2Status = LEGACY: it predates
 * SpO2 and is never recomputed automatically (see
 * ReadingPostProcessor's header for the on-demand path when a legacy
 * reading is opened).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN irRaw INTEGER")
        db.execSQL("ALTER TABLE samples ADD COLUMN redRaw INTEGER")
        db.execSQL("ALTER TABLE samples ADD COLUMN greenRaw INTEGER")
        db.execSQL("ALTER TABLE samples ADD COLUMN sqi REAL")

        db.execSQL("ALTER TABLE readings ADD COLUMN spo2Status TEXT")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2Median REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2Min REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2Max REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2AcceptedPct REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN rMedian REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN piIrMedian REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN piRedMedian REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2Curve TEXT")
        db.execSQL("ALTER TABLE readings ADD COLUMN spo2CalibrationInfo TEXT")
        db.execSQL("ALTER TABLE readings ADD COLUMN qualityVerdict TEXT")
        db.execSQL("ALTER TABLE readings ADD COLUMN qualityJson TEXT")

        db.execSQL("UPDATE readings SET spo2Status = 'LEGACY'")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spo2_windows` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `readingId` INTEGER NOT NULL,
                `tSec` REAL NOT NULL,
                `r` REAL NOT NULL,
                `spo2` REAL NOT NULL,
                `piIr` REAL NOT NULL,
                `piRed` REAL NOT NULL,
                `valid` INTEGER NOT NULL,
                `rejectReason` TEXT NOT NULL,
                FOREIGN KEY(`readingId`) REFERENCES `readings`(`id`) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_spo2_windows_readingId` ON `spo2_windows` (`readingId`)")
    }
}
