package com.eee312.ppgmonitor.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** exportSchema=false - a course project with one schema version, not
 * worth the schemas/ directory ceremony a real migration story needs. */
@Database(entities = [ReadingEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ppgmonitor.db"
                ).build().also { instance = it }
            }
    }
}
