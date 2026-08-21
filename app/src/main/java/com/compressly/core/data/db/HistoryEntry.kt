package com.compressly.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One history row per compressed file (success or failure). */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val jobId: Long,
    val mediaType: String,
    val fileName: String,
    val inputUri: String,
    val inputSize: Long,
    val outputUri: String?,
    val outputSize: Long,
    val status: String,
    val error: String?,
    val settingsSummary: String,
    val createdAt: Long,
    val durationMs: Long
) {
    val savedBytes: Long
        get() = if (status == STATUS_DONE) (inputSize - outputSize).coerceAtLeast(0) else 0L

    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_RUNNING = "RUNNING"
    }
}
