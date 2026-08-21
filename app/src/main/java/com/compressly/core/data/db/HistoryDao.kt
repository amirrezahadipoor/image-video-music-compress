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

    @Query("SELECT COALESCE(SUM(inputSize - outputSize), 0) FROM history WHERE status = :done")
    fun observeTotalSaved(done: String = HistoryEntry.STATUS_DONE): Flow<Long>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Update
    suspend fun update(entry: HistoryEntry)

    @Query("UPDATE history SET status = :status, error = :error WHERE status = 'RUNNING'")
    suspend fun markInterrupted(status: String, error: String?)

    @Query("DELETE FROM history")
    suspend fun clear()
}
