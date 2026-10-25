package com.compressly.core.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.compressly.core.data.HistoryRepository
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.CompressionCancelledException
import com.compressly.core.engine.ForegroundServiceUnavailableException
import com.compressly.core.engine.Compressor
import com.compressly.core.engine.photo.PhotoBatch
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.errorKeyOf
import com.compressly.core.engine.model.AudioFormat
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Seed for the first job id of this process. A member function would be called
 * from a property initializer, so it lives at file scope instead.
 */
private fun newJobIdSeed(): Long =
    ((System.currentTimeMillis() * 1000L) + java.util.Random().nextInt(1000)) and Long.MAX_VALUE

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
    /** Per-item controls: with parallel photo batches more than one item can be in flight. */
    private val itemControls = ConcurrentHashMap<Long, ConcurrentHashMap<Long, JobControl>>()
    private val jobPaused = ConcurrentHashMap<Long, Boolean>()
    private val cancelledItems = ConcurrentHashMap<Long, MutableSet<Long>>()

    /**
     * JOBID-UNIQ-FIX (the root cause of "the single file's result showed the
     * stats of my old folder"): the id used to restart at 1 on every process
     * start, while history rows keep their jobId in Room forever. So job 1 of
     * this run matched job 1 of a previous run in `getByJob` / `getFirstDoneByJob`
     * — the batch branch of the result screen fired for a one-file job, the
     * batch summary mixed in unrelated old files, and "share all" sent the
     * previous folder's files. Ids are now globally unique: milliseconds since
     * the epoch scaled by 1000 plus a random tail, then a monotonic counter.
     * Legacy small ids already stored in the DB can never collide with these.
     */
    private val nextJobId = AtomicLong(newJobIdSeed())

    fun enqueue(
        mediaType: MediaType,
        items: List<InputItem>,
        settings: CompressionSettings
    ): Long {
        val jobId = nextJobId.getAndIncrement()
        val itemStates = items.map { ItemState(it.itemId, it.displayName, ItemPhase.QUEUED) }
        _jobs.update { it + (jobId to JobState(jobId, mediaType, JobStatus.RUNNING, items = itemStates, preset = settings.preset)) }
        // CANCEL-RACE-FIX: register the job control BEFORE the coroutine is
        // launched. appScope.launch() is asynchronous — between the launch
        // and the first line of runJob there is a window in which the user
        // can hit cancel; with no control registered yet, cancel() found
        // controls[jobId] == null and was silently dropped, so the job
        // started anyway and the UI showed a dead cancel button.
        controls[jobId] = JobControl()
        // C1/C13 (SERVICE-GUARD-FIX): startForegroundService throws
        // ForegroundServiceStartNotAllowedException on Android 12+ when the app
        // is in the background, and a SecurityException if the OEM locked the
        // FGS type down. The exception is what the caller's runCatching turned
        // into "the button did nothing" -- while this map kept a RUNNING row
        // that no coroutine would ever finish. Fail the enqueue cleanly instead
        // so nothing is left behind and the caller can tell the user.
        if (!startService()) {
            _jobs.update { it - jobId }
            controls.remove(jobId)
            throw ForegroundServiceUnavailableException()
        }
        appScope.launch { runJob(jobId, items, settings) }
        return jobId
    }

    fun job(jobId: Long): JobState? = _jobs.value[jobId]

    fun pause(jobId: Long) {
        jobPaused[jobId] = true
        controls[jobId]?.pause()
        itemControls[jobId]?.values?.forEach { it.pause() }
        // PAUSED-STATE-FIX: JobStatus.PAUSED was declared and read in four
        // places but never written, so every `status == PAUSED` check was dead.
        // Record it for real (only from RUNNING, so a cancel is never undone).
        updateJob(jobId) {
            if (it.status == JobStatus.RUNNING) it.copy(isPaused = true, status = JobStatus.PAUSED)
            else it.copy(isPaused = true)
        }
    }

    fun resume(jobId: Long) {
        jobPaused[jobId] = false
        controls[jobId]?.resume()
        itemControls[jobId]?.values?.forEach { it.resume() }
        updateJob(jobId) {
            if (it.status == JobStatus.PAUSED) it.copy(isPaused = false, status = JobStatus.RUNNING)
            else it.copy(isPaused = false)
        }
    }

    fun cancel(jobId: Long) {
        updateJob(jobId) { it.copy(status = JobStatus.CANCELLING, isPaused = false) }
        controls[jobId]?.cancel()
        itemControls[jobId]?.values?.forEach { it.cancel() }
    }

    /**
     * Cancels a single file inside a batch. Queued items are skipped;
     * the running item is aborted and the remaining items continue.
     */
    fun cancelItem(jobId: Long, itemId: Long) {
        cancelledItems.getOrPut(jobId) { ConcurrentHashMap.newKeySet() }.add(itemId)
        // PARALLEL-PHOTO-SAFE: cancel only the control of THIS item — in a
        // parallel batch the sibling item must keep running.
        itemControls[jobId]?.get(itemId)?.cancel()
        updateItem(jobId, itemId) { it.copy(phase = ItemPhase.CANCELLED, fraction = 0f) }
    }

    // ------------------------------------------------------------------

    private suspend fun runJob(jobId: Long, items: List<InputItem>, settings: CompressionSettings) {
        // Re-use the control registered in enqueue() (CANCEL-RACE-FIX) so a
        // cancel that landed in the launch window is already observed here;
        // fall back to creating one if it was cleared in the meantime.
        val control = controls[jobId]
            ?: JobControl(startPaused = jobPaused[jobId] == true).also { controls[jobId] = it }
        val anySuccess = AtomicBoolean(false)
        val anyFailure = AtomicBoolean(false)
        val anyCancelled = AtomicBoolean(false)
        val jobCancelled = AtomicBoolean(false)
        // COORD-L1 FIX: use a live lookup instead of a snapshot.
        // A snapshot taken here is stale once cancelItem() adds an ID after
        // the snapshot is captured but before the item is processed —  the
        // subsequent CompressionCancelledException is then misrouted as a
        // whole-job cancel (jobCancelled=true) instead of a single-item cancel.
        // isItemCancelled() reads the live ConcurrentHashMap on every call,
        // which is safe because ConcurrentHashMap.newKeySet() is thread-safe.
        fun isItemCancelled(itemId: Long) =
            cancelledItems[jobId]?.contains(itemId) == true

        val compressor = Compressor(context)

        /** Runs one item to completion (sequential path and parallel lane). */
        suspend fun processOne(item: InputItem) {
            // PARALLEL-PHOTO-FIX: each item has its OWN control so that in a
            // parallel batch cancelling one photo does not abort its sibling,
            // and pause/resume reach every in-flight item.
            val itemControl = JobControl(startPaused = jobPaused[jobId] == true)
            itemControls.getOrPut(jobId) { ConcurrentHashMap() }[item.itemId] = itemControl
            var originalRetained = false
            try {
                // CRASH-RECOVERY-FIX: every item gets a RUNNING history row
                // BEFORE any work starts. If the process dies mid-compression,
                // markInterruptedOnStartup() now has a row to find and can mark
                // it INTERRUPTED. Previously no row ever carried STATUS_RUNNING,
                // so the whole "resume after crash" feature was dead code and a
                // half-written file (API 26-28 has no IS_PENDING) stayed
                // visible in the gallery with no trace in history.
                val runningRow = historyRepository.insert(runningEntry(jobId, item))

                // Items individually cancelled while queued are skipped.
                // CANCEL-RACE-FIX: a whole-job cancel that landed in the
                // enqueue()->processOne window is honoured here too — the
                // job control registered in enqueue() is already marked.
                if (isItemCancelled(item.itemId) || control.isCancelled) {
                    anyCancelled.set(true)
                    updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.CANCELLED) }
                    historyRepository.update(
                        entryFrom(settings.mediaType(), cancelledResult(jobId, item))
                            .copy(id = runningRow)
                    )
                    return
                }
                updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.PREPARING, fraction = 0f) }
                val result = try {
                    itemControl.checkActive()
                    compressor.compressItem(jobId, item, settings, itemControl) { phase: ItemPhase, frac: Float ->
                        // MONOTONIC-FIX: a retry pass restarts its own fraction at
                        // zero — the transcoder maps its second attempt into the
                        // same 0..1 range — so the bar used to jump backwards in
                        // the middle of a file. Progress of one item is a promise
                        // that only moves forward; terminal phases still win.
                        updateItem(jobId, item.itemId) {
                            val forward = if (phase == ItemPhase.COMPRESSING) maxOf(it.fraction, frac) else frac
                            it.copy(phase = phase, fraction = forward)
                        }
                    }
                } catch (e: CompressionCancelledException) {
                    if (isItemCancelled(item.itemId)) {
                        // Only this item was cancelled; keep the rest of the batch.
                        anyCancelled.set(true)
                        updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.CANCELLED) }
                        cancelledResult(jobId, item)
                    } else {
                        jobCancelled.set(true)
                        updateItem(jobId, item.itemId) { it.copy(phase = ItemPhase.CANCELLED) }
                        cancelledResult(jobId, item)
                    }
                } catch (t: Throwable) {
                    val key = errorKeyOf(t)
                    // DIAG-FIX: the error key is deliberately coarse (the UI
                    // maps it to one string), but the REAL cause must be
                    // logged with its stack so a device-specific MediaCodec
                    // failure is not a black box. Log.e keeps it in the
                    // release build too, so a user report can be diagnosed.
                    val detail = t::class.java.simpleName + ": " + (t.message ?: "")
                    android.util.Log.e(
                        "CompressJob",
                        "item failed (${settings.mediaType()}, key=$key): " + detail,
                        t
                    )
                    updateItem(jobId, item.itemId) {
                        it.copy(phase = ItemPhase.FAILED, error = key, errorDetail = detail)
                    }
                    CompressionResult(
                        itemId = item.itemId, jobId = jobId, fileName = item.displayName,
                        inputUri = item.uri, inputSize = item.sizeBytes,
                        success = false, error = key
                    )
                }
                if (result.success) {
                    anySuccess.set(true)
                    // REPLACE-ORIGINAL: after a genuinely successful compression the
                    // user may have asked for the original to be deleted so the
                    // compressed copy takes its place. Only delete when a NEW output
                    // was actually published (outputUri present and different from the
                    // input) — the keep-original path returns the input URI itself,
                    // and deleting that would destroy the file we just kept.
                    if (settings.replaceOriginal &&
                        result.outputUri != null &&
                        result.outputUri != result.inputUri
                    ) {
                        // RETAIN-MARKER-FIX: when the source could not be removed
                        // (no write/delete grant on a MediaStore row this app does
                        // not own) the old code stayed silent and the user just
                        // found the originals still in the gallery — the exact
                        // "my 1700 duplicates" complaint. Record it on the row so
                        // the result screen can say it plainly and offer the fix.
                        originalRetained = !deleteOriginalOptional(result.inputUri, settings.outputFolder)
                    }
                } else if (result.error == "cancelled") {
                    anyCancelled.set(true)
                } else {
                    anyFailure.set(true)
                }
                // Finalize the RUNNING row created at the start of this item.
                historyRepository.update(
                    entryFrom(settings.mediaType(), result, originalRetained).copy(id = runningRow)
                )
            } finally {
                itemControls[jobId]?.remove(item.itemId)
            }
        }

        try {
            // PARALLELISM (CONCURRENCY-5): a batch of two or more runs several
            // files at once instead of one after another. The budget is global
            // (EngineGate: five files across the whole app) and each item asks
            // for as many slots as it is heavy, so:
            //   · ordinary photo / MP3  -> one slot  (five files at a time)
            //   · a >= 16 MP photo      -> the whole budget (runs alone)
            //   · a big photo batch on a low-memory phone -> two at a time
            //   · AAC (hardware encoder) -> two at a time
            //   · video -> as many as the device's codecs actually allow
            // The order of completion still yields identical results/history —
            // only throughput changes.
            if (items.size >= 2) {
                // BATCH-SCHED-FIX: heaviest first — the long-running items land
                // in the slots at the start instead of queueing behind small
                // ones, so the batch drains smoothly instead of stalling on a
                // huge tail.
                val ordered = PhotoBatch.heaviestFirst(items) { it.sizeBytes }
                val weights = slotWeightsFor(settings, ordered)
                coroutineScope {
                    ordered.mapIndexed { index, item ->
                        launch(Dispatchers.Default) {
                            // CANCEL-RACE-FIX: unconditional — processOne's
                            // pre-check handles a cancelled job (and, unlike the
                            // old else-branch, it also finalizes the RUNNING
                            // history row created above).
                            EngineGate.withSlots(weights[index]) { processOne(item) }
                        }
                    }.joinAll()
                }
            } else {
                // QUEUE-FIX: heavy media first. For video/audio the largest
                // file is the long pole of the batch, so it starts right away
                // instead of queueing behind the small ones: the user sees
                // real progress at once and the batch drains smoothly.
                val ordered = PhotoBatch.heaviestFirst(items) { it.sizeBytes }
                // B9 (GLOBAL-BUDGET): a single-file job used to run OUTSIDE the
                // gate, so two separate one-file jobs (the most common way to
                // hit B9: pick a video, start, go back, pick another) could take
                // two hardware codecs at once with no budget at all. Every item
                // in the app now asks the process-wide gate, whichever job it
                // belongs to.
                val weights = slotWeightsFor(settings, ordered)
                for ((index, item) in ordered.withIndex()) {
                    if (jobCancelled.get()) break
                    EngineGate.withSlots(weights[index]) { processOne(item) }
                }
            }
            if (jobCancelled.get()) {
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
        } catch (t: Throwable) {
            // RUNJOB-CATCHALL-FIX: only the per-item work had a catch; anything
            // thrown by the orchestration itself (a Room SQLiteFullException
            // from historyRepository.insert/update, the ActivityManager probe,
            // an IllegalArgumentException in PhotoBatch) escaped into
            // appScope — which has no CoroutineExceptionHandler — and killed the
            // process while the foreground notification stayed pinned and the
            // job never reached a terminal state. Failing the job honestly is
            // always better than crashing the app.
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("CompressJob", "job $jobId aborted by orchestration error", t)
            anyFailure.set(true)
            _jobs.update { jobs ->
                val job = jobs[jobId] ?: return@update jobs
                jobs + (jobId to job.copy(
                    items = job.items.map { st ->
                        if (st.phase == ItemPhase.QUEUED || st.phase == ItemPhase.PREPARING ||
                            st.phase == ItemPhase.COMPRESSING || st.phase == ItemPhase.FINALIZING
                        ) st.copy(phase = ItemPhase.FAILED, error = "generic", errorDetail = t::class.java.simpleName)
                        else st
                    }
                ))
            }
        } finally {
            controls.remove(jobId)
            itemControls.remove(jobId)
            jobPaused.remove(jobId)
            cancelledItems.remove(jobId)
            // PARTIAL-FIX: delegated to the pure JobStatusResolver so the exact
            // precedence is unit-tested. Previously any failure was swallowed
            // whenever at least one item succeeded (anySuccess short-circuited
            // the FAILED branch), so a mixed batch was reported as COMPLETED.
            val finalStatus = JobStatusResolver.resolve(
                anySuccess = anySuccess.get(),
                anyFailure = anyFailure.get(),
                anyCancelled = anyCancelled.get(),
                jobCancelled = jobCancelled.get()
            )
            updateJob(jobId) { it.copy(status = finalStatus, isPaused = false) }
            stopServiceIfIdle()
            // Prune terminal jobs from memory (results live in Room). 3 minutes
            // gives the user time to review the progress screen before it
            // degrades to the "job not found" fallback.
            appScope.launch {
                delay(3 * 60_000)
                _jobs.update { it - jobId }
            }
        }
    }

    private fun cancelledResult(jobId: Long, item: InputItem) = CompressionResult(
        itemId = item.itemId, jobId = jobId, fileName = item.displayName,
        inputUri = item.uri, inputSize = item.sizeBytes,
        success = false, error = "cancelled"
    )

    /**
     * Placeholder history row written BEFORE an item is processed. Carries
     * STATUS_RUNNING so a process death mid-way is discoverable on next launch
     * (see HistoryRepository.markInterruptedOnStartup).
     */
    private fun runningEntry(jobId: Long, item: InputItem): HistoryEntry = HistoryEntry(
        jobId = jobId,
        mediaType = item.mediaType.name,
        fileName = item.displayName,
        inputUri = item.uri.toString(),
        inputSize = item.sizeBytes,
        outputUri = null,
        outputSize = 0L,
        status = HistoryEntry.STATUS_RUNNING,
        error = null,
        settingsSummary = "",
        createdAt = System.currentTimeMillis(),
        durationMs = 0L
    )

    private fun CompressionSettings.mediaType(): MediaType = when (this) {
        is CompressionSettings.Photo -> MediaType.PHOTO
        is CompressionSettings.Video -> MediaType.VIDEO
        is CompressionSettings.Audio -> MediaType.AUDIO
    }

    private fun entryFrom(
        mediaType: MediaType,
        result: CompressionResult,
        originalRetained: Boolean = false
    ): HistoryEntry {
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
            // Reuses the existing free-text column (no schema change): the
            // result screen turns it into a plain-language warning plus the
            // "remove them now" action. Failed rows keep their real error key.
            error = if (originalRetained && result.success) HistoryEntry.ERROR_ORIGINAL_RETAINED else result.error,
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

    /**
     * REPLACE-ORIGINAL: best-effort deletion of a source file. Never fatal: the
     * app only holds read grants for picked URIs, so a contentResolver.delete can
     * return 0 or throw (RecoverableSecurityException on Q, SecurityException on
     * 30+ without a delete grant). We never fail the item — worst case the
     * original simply stays, which is safe.
     */
    private fun deleteOriginalOptional(uri: Uri, jobTree: String?): Boolean {
        // Use the tree-aware delete in OutputStore rather than a bare
        // contentResolver.delete, which silently fails for SAF tree / picker
        // documents and was leaving the original behind (the duplicate-photos
        // bug). The in-place replace normally avoids this entirely.
        return com.compressly.core.data.OutputStore.delete(context, uri, jobTree)
    }

    private fun updateItem(jobId: Long, itemId: Long, transform: (ItemState) -> ItemState) {
        _jobs.update { jobs ->
            val job = jobs[jobId] ?: return@update jobs
            jobs + (jobId to job.copy(items = job.items.map { if (it.itemId == itemId) transform(it) else it }))
        }
    }

    /** @return false when the OS refused to start the foreground service. */
    private fun startService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CompressionJobService::class.java)
                .setAction(CompressionJobService.ACTION_START)
        )
    }.onFailure {
        android.util.Log.e("JobCoordinator", "foreground service refused to start", it)
    }.isSuccess

    /**
     * CONCURRENCY-5: how much of the global budget each item of [ordered] takes.
     *
     * Photos are probed for their pixel count on IO (opening every source is
     * pure I/O), videos ask the device how many codec instances it advertises,
     * and audio splits by encoder (LAME is pure CPU, AAC is a shared hardware
     * block).
     */
    private suspend fun slotWeightsFor(
        settings: CompressionSettings,
        ordered: List<InputItem>
    ): List<Int> = when (settings) {
        is CompressionSettings.Photo -> {
            val pixelCounts = withContext(Dispatchers.IO) {
                ordered.map { PhotoBatch.pixelCountOf(context, it.uri) }
            }
            val lowMemoryLargeBatch = PhotoBatch.isLowMemoryLargeBatch(
                memoryClassMb = runCatching {
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
                }.getOrNull(),
                batchSize = ordered.size
            )
            pixelCounts.map { PhotoBatch.weightFor(it, lowMemoryLargeBatch) }
        }
        is CompressionSettings.Video -> {
            val slots = com.compressly.core.engine.video.VideoConcurrency.slotsFor(settings.settings.codec)
            List(ordered.size) { com.compressly.core.engine.video.VideoConcurrency.weightFor(slots) }
        }
        is CompressionSettings.Audio -> {
            // MP3 is the embedded pure-Java LAME port (independent CPU jobs);
            // AAC uses one shared hardware encoder, so two at a time is the
            // most that helps.
            val weight = if (settings.settings.format == AudioFormat.MP3) 1 else 3
            List(ordered.size) { weight }
        }
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
