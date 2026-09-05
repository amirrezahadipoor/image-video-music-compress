package com.compressly.core.util

import com.compressly.core.data.db.HistoryEntry

/**
 * The one place that decides what "this job saved" means.
 *
 * It used to be computed twice, differently: the headline percentage summed each
 * file's own saving (where a file that grew contributes nothing) while the
 * before/after cards summed the raw sizes, so a batch could show "before 1.0 GB,
 * after 1.1 GB" next to "saved 200 MB". Both numbers now come from here, and the
 * rule is stated once: the saving of a job is what the whole set of finished
 * files weighed minus what it weighs now.
 *
 * Failed and cancelled rows are excluded from the byte maths but counted in
 * [Totals.total], because "3 of 4 files" is part of an honest summary even when
 * those rows carry no output size.
 */
object JobTotals {

    data class Totals(
        val done: Int,
        val total: Int,
        val before: Long,
        val after: Long,
        val saved: Long,
        /** Finished rows whose original could not be removed (see [HistoryEntry.originalRetained]). */
        val retained: Int
    ) {
        /** Share of the original bytes that disappeared, 0..1. */
        val reduction: Double
            get() = if (before > 0) (saved.toDouble() / before).coerceIn(0.0, 1.0) else 0.0

        /** True when a batch ended up bigger than it started (a real possibility). */
        val grew: Boolean get() = saved < 0
    }

    /**
     * Batch output estimate derived from the ONE file the analysis measured.
     *
     * Scaling the first file by N invented folders: a single 60 MB clip at the
     * front made 40 phone clips look like 2.4 GB, and that number is what the
     * free-space gate reads — so the user got a false "not enough space" dialog,
     * whose "compress anyway" answer used to skip the MediaStore consent prompt
     * entirely (see CompressionSettingsScreen). Reuse what was actually learned
     * instead: the before-to-after RATIO of the analysed file, applied to the
     * real total size of the selection. Falls back to N-times only when the
     * analysis has no ratio to offer (sizes unknown).
     */
    fun estimateBatchBytes(totalOriginal: Long, firstOriginal: Long, firstEstimate: Long, count: Int): Long {
        if (count <= 1) return firstEstimate.coerceAtLeast(0L)
        if (totalOriginal > 0L && firstOriginal > 0L && firstEstimate > 0L) {
            val ratio = firstEstimate.toDouble() / firstOriginal.toDouble()
            return (totalOriginal.toDouble() * ratio).toLong().coerceAtLeast(0L)
        }
        return firstEstimate.coerceAtLeast(0L) * count
    }

    fun of(rows: List<HistoryEntry>): Totals {
        val done = rows.filter { it.status == HistoryEntry.STATUS_DONE }
        val before = done.sumOf { it.inputSize }
        val after = done.sumOf { it.outputSize }
        return Totals(
            done = done.size,
            total = rows.size,
            before = before,
            after = after,
            saved = before - after,
            retained = done.count { it.originalRetained }
        )
    }
}
