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

/**
 * v2 -> v3: raw-sample export, measured sample rate, dropout flags and
 * the File source label. ALTER TABLE ADD COLUMN only (nullable, no
 * default) - every existing reading, sample and SpO2 window survives
 * unchanged; the new columns are simply null for them.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN tSrc REAL")
        db.execSQL("ALTER TABLE samples ADD COLUMN accX REAL")
        db.execSQL("ALTER TABLE samples ADD COLUMN accY REAL")
        db.execSQL("ALTER TABLE samples ADD COLUMN accZ REAL")
        db.execSQL("ALTER TABLE samples ADD COLUMN flags INTEGER")
        db.execSQL("ALTER TABLE readings ADD COLUMN measuredRateHz REAL")
        db.execSQL("ALTER TABLE readings ADD COLUMN sourceLabel TEXT")
    }
}

/**
 * v3 -> v4: spo2_windows.r / spo2 / piIr / piRed become nullable. SQLite
 * has no ALTER COLUMN, so the table is rebuilt the standard way inside the
 * migration's transaction: create the new table, copy every row, drop the
 * old one, rename, recreate the index. No row is lost or changed.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spo2_windows_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `readingId` INTEGER NOT NULL,
                `tSec` REAL NOT NULL,
                `r` REAL,
                `spo2` REAL,
                `piIr` REAL,
                `piRed` REAL,
                `valid` INTEGER NOT NULL,
                `rejectReason` TEXT NOT NULL,
                FOREIGN KEY(`readingId`) REFERENCES `readings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO `spo2_windows_new` (`id`, `readingId`, `tSec`, `r`, `spo2`, `piIr`, `piRed`, `valid`, `rejectReason`) " +
                "SELECT `id`, `readingId`, `tSec`, `r`, `spo2`, `piIr`, `piRed`, `valid`, `rejectReason` FROM `spo2_windows`"
        )
        db.execSQL("DROP TABLE `spo2_windows`")
        db.execSQL("ALTER TABLE `spo2_windows_new` RENAME TO `spo2_windows`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_spo2_windows_readingId` ON `spo2_windows` (`readingId`)")
    }
}
