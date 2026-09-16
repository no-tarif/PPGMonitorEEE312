package com.eee312.ppgmonitor.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eee312.ppgmonitor.dsp.spo2.Spo2RejectReason

/** One Spo2Window (dsp/spo2/Spo2Result.kt) from the batch SpO2 pass over
 * one [ReadingEntity]. Cascade-deletes with its parent reading, same
 * pattern as [SampleEntity]. New in schema v2 - never populated for
 * LEGACY readings unless their on-demand quality/SpO2 pass is run. */
@Entity(
    tableName = "spo2_windows",
    foreignKeys = [
        ForeignKey(
            entity = ReadingEntity::class,
            parentColumns = ["id"],
            childColumns = ["readingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("readingId")]
)
data class Spo2WindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingId: Long,
    val tSec: Double,
    val r: Double,
    val spo2: Double,
    val piIr: Double,
    val piRed: Double,
    val valid: Boolean,
    val rejectReason: Spo2RejectReason
)
