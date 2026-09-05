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
        get() = if (status == STATUS_DONE) (inputSize - outputSize).coerceAtLeast(0L) else 0L

    /**
     * Not a failure: the file WAS compressed, but the original could not be
     * removed because the app holds no write/delete grant on a MediaStore row it
     * does not own. The result screen shows this as a plain warning with a
     * one-tap fix instead of staying silent (which is how "replace original"
     * looked like it worked while the gallery kept every original).
     *
     * It rides on the existing free-text [error] column on purpose: a new Room
     * column would need a hand-written schema + migration, and the identity hash
     * in `schemas/*.json` can only be produced by a real build. Until a build
     * can generate it, reusing the column is the safe choice.
     */
    val originalRetained: Boolean
        get() = status == STATUS_DONE && error == ERROR_ORIGINAL_RETAINED

    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_RUNNING = "RUNNING"

        /** Marker stored in [error] for a successful row that kept its original. */
        const val ERROR_ORIGINAL_RETAINED = "original_retained"
    }
}
