package com.compressly.core.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.compressly.core.data.HistoryRepository
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.CompressionCancelledException
import com.compressly.core.engine.Compressor
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.errorKeyOf
import com.compressly.core.engine.model.CompressionResult
import com.compressly.core.engine.model.CompressionSettings
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.ItemState
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.JobStatus
import com.compressly.core.engine.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central job orchestrator. Enqueues compression jobs, runs them on a
 * background scope, exposes live progress via StateFlow, and keeps the
 * foreground service alive while work is pending. Fully offline.
 */
class JobCoordinator(
    private val context: Context,
    private val historyRepository: HistoryRepository
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _jobs = MutableStateFlow<Map<Long, JobState>>(emptyMap())
    val jobs: StateFlow<Map<Long, JobState>> = _jobs.asStateFlow()

    private val controls = ConcurrentHashMap<Long, JobControl>()
    private val nextJobId = AtomicLong(1)

    fun enqueue(
        mediaType: MediaType,
        items: List<InputItem>,
        settings: CompressionSettings
    ): Long {
        val jobId = nextJobId.getAndIncrement()
        val itemStates = items.map { ItemState(it.itemId, it.displayName, ItemPhase.QUEUED) }
        _jobs.update { it + (jobId to JobState(jobId, mediaType, JobStatus.RUNNING, items = itemStates)) }
        startService()
        appScope.launch { runJob(jobId, items, settings) }
        return jobId
    }

    fun job(jobId: Long): JobState? = _jobs.value[jobId]

    fun pause(jobId: Long) {
        controls[jobId]?.pause()
        updateJob(jobId) { it.copy(isPaused = true) }
    }

    fun resume(jobId: Long) {
        controls[jobId]?.resume()
        updateJob(jobId) { it.copy(isPaused = false) }
    }

    fun cancel(jobId: Long) {
        updateJob(jobId) { it.copy(status = JobStatus.CANCELLING, isPaused = false) }
        controls[jobId]?.cancel()
    }

    // ------------------------------------------------------------------

    private suspend fun runJob(jobId: Long, items: List<InputItem>, settings: CompressionSettings) {
        val control = JobControl()
        controls[jobId] = control
        var cancelled = false
        var anySuccess = false
        var anyFailure = false
        try {
            val compressor = Compressor(context)
            for (item in items) {
                control.checkActive()
                updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.PREPARING, fraction = 0f) }
                val result = try {
                    compressor.compressItem(jobId, item, settings, control) { phase, frac ->
                        updateItem(jobId, item.itemId) { it.copy(phase = phase, fraction = frac) }
                    }
                } catch (e: CompressionCancelledException) {
                    cancelled = true
                    updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.CANCELLED) }
                    CompressionResult(
                        itemId = item.itemId, jobId = jobId, fileName = item.displayName,
                        inputUri = item.uri, inputSize = item.sizeBytes,
                        success = false, error = "cancelled"
                    )
                } catch (t: Throwable) {
                    val key = errorKeyOf(t)
                    updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.FAILED, error = key) }
                    CompressionResult(
                        itemId = item.itemId, jobId = jobId, fileName = item.displayName,
                        inputUri = item.uri, inputSize = item.sizeBytes,
                        success = false, error = key
                    )
                }
                if (result.success) anySuccess = true else anyFailure = true
                historyRepository.insert(entryFrom(settings.mediaType(), result))
                if (cancelled) break
            }
            if (cancelled) {
                // Mark the remaining (unprocessed) items as cancelled for clarity.
                _jobs.update { jobs ->
                    val job = jobs[jobId] ?: return@update jobs
                    jobs + (jobId to job.copy(
                        items = job.items.map { st ->
                            if (st.phase == ItemPhase.QUEUED || st.phase == ItemPhase.PREPARING) {
                                st.copy(phase = ItemPhase.CANCELLED)
                            } else st
                        }
                    ))
                }
            }
        } finally {
            controls.remove(jobId)
            val finalStatus = when {
                cancelled -> JobStatus.CANCELLED
                !anySuccess && anyFailure -> JobStatus.FAILED
                else -> JobStatus.COMPLETED
            }
            updateJob(jobId) { it.copy(status = finalStatus, isPaused = false) }
            stopServiceIfIdle()
            // Prune terminal jobs from memory (results live in Room).
            appScope.launch {
                delay(60_000)
                _jobs.update { it - jobId }
            }
        }
    }

    private fun CompressionSettings.mediaType(): MediaType = when (this) {
        is CompressionSettings.Photo -> MediaType.PHOTO
        is CompressionSettings.Video -> MediaType.VIDEO
        is CompressionSettings.Audio -> MediaType.AUDIO
    }

    private fun entryFrom(mediaType: MediaType, result: CompressionResult): HistoryEntry {
        val status = when {
            result.error == "cancelled" -> HistoryEntry.STATUS_CANCELLED
            result.success -> HistoryEntry.STATUS_DONE
            else -> HistoryEntry.STATUS_FAILED
        }
        return HistoryEntry(
            jobId = result.jobId,
            mediaType = mediaType.name,
            fileName = result.fileName,
            inputUri = result.inputUri.toString(),
            inputSize = result.inputSize,
            outputUri = result.outputUri?.toString(),
            outputSize = result.outputSize,
            status = status,
            error = result.error,
            settingsSummary = result.settingsSummary,
            createdAt = System.currentTimeMillis(),
            durationMs = result.durationMs
        )
    }

    private fun updateJob(jobId: Long, transform: (JobState) -> JobState) {
        _jobs.update { jobs ->
            val job = jobs[jobId] ?: return@update jobs
            jobs + (jobId to transform(job))
        }
    }

    private fun updateItem(jobId: Long, itemId: Long, transform: (ItemState) -> ItemState) {
        _jobs.update { jobs ->
            val job = jobs[jobId] ?: return@update jobs
            jobs + (jobId to job.copy(items = job.items.map { if (it.itemId == itemId) transform(it) else it }))
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CompressionJobService::class.java)
                .setAction(CompressionJobService.ACTION_START)
        )
    }

    private fun stopServiceIfIdle() {
        val busy = _jobs.value.values.any {
            it.status == JobStatus.RUNNING || it.status == JobStatus.PAUSED || it.status == JobStatus.CANCELLING
        }
        if (!busy) {
            context.stopService(Intent(context, CompressionJobService::class.java))
        }
    }
}
