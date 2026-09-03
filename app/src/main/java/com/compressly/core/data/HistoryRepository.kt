package com.compressly.core.data

import com.compressly.core.data.db.HistoryDao
import com.compressly.core.data.db.HistoryEntry
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {

    val all: Flow<List<HistoryEntry>> = dao.observeAll()
    val recent: Flow<List<HistoryEntry>> = dao.observeRecent()
    val totalSaved: Flow<Long> = dao.observeTotalSaved()

    fun observeEntry(id: Long): Flow<HistoryEntry?> = dao.observeById(id)

    suspend fun getById(id: Long): HistoryEntry? = dao.getById(id)

    suspend fun getByJob(jobId: Long): List<HistoryEntry> = dao.getByJob(jobId)

    suspend fun getFirstDoneByJob(jobId: Long): HistoryEntry? = dao.getFirstDoneByJob(jobId)

    suspend fun insert(entry: HistoryEntry): Long = dao.insert(entry)

    suspend fun update(entry: HistoryEntry) = dao.update(entry)

    suspend fun clear() = dao.clear()

    /** Removes the given history rows (multi-select bulk delete). */
    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.deleteByIds(ids)
    }

    /**
     * Called on app start: any job that was running when the process died is
     * marked interrupted with a clear message (never a silent half-done file,
     * never a corrupt output left visible in the gallery).
     */
    suspend fun markInterruptedOnStartup() {
        dao.markInterrupted(
            HistoryEntry.STATUS_INTERRUPTED,
            "interrupted"
        )
    }
}
