package com.eee312.ppgmonitor.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingEntity::class, SampleEntity::class, Spo2WindowEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun sampleDao(): SampleDao
    abstract fun spo2WindowDao(): Spo2WindowDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ppgmonitor.db"
                )
                    // Rule Zero: never fallbackToDestructiveMigration - an
                    // existing reading (e.g. the "Reading 35-43" a
                    // presentation depends on) must survive every upgrade.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
