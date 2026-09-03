package com.compressly.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT 4")
    fun observeRecent(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<HistoryEntry?>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntry?

    @Query("SELECT * FROM history WHERE jobId = :jobId ORDER BY id ASC")
    suspend fun getByJob(jobId: Long): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE jobId = :jobId AND status = :done ORDER BY id ASC LIMIT 1")
    suspend fun getFirstDoneByJob(jobId: Long, done: String = HistoryEntry.STATUS_DONE): HistoryEntry?

    // HIST-1 FIX: use MAX(0, inputSize-outputSize) per row so that a single
    // PNG/lossless result whose outputSize > inputSize cannot drag the running
    // total below zero. Plain SUM would subtract those rows from the total.
    @Query("SELECT COALESCE(SUM(MAX(0, inputSize - outputSize)), 0) FROM history WHERE status = :done")
    fun observeTotalSaved(done: String = HistoryEntry.STATUS_DONE): Flow<Long>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Update
    suspend fun update(entry: HistoryEntry)

    // BUG-4 FIX: Use the STATUS_RUNNING constant instead of a raw string literal
    // so this query stays correct if the constant value is ever changed.
    @Query("UPDATE history SET status = :status, error = :error WHERE status = :running")
    suspend fun markInterrupted(
        status: String,
        error: String?,
        running: String = HistoryEntry.STATUS_RUNNING
    )

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
