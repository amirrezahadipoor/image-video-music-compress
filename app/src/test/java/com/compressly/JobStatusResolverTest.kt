package com.compressly

import com.compressly.core.engine.model.JobStatus
import com.compressly.core.service.JobStatusResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the whole-job terminal status mapping. The regression this guards: the
 * old `when` treated any successful item as enough to report COMPLETED, so a
 * mixed batch (some files failed) was shown as "done". Now a mixed batch is
 * PARTIAL, and precedence is explicit and frozen here.
 */
class JobStatusResolverTest {

    @Test
    fun allSuccess_isCompleted() {
        assertEquals(
            JobStatus.COMPLETED,
            JobStatusResolver.resolve(anySuccess = true, anyFailure = false, anyCancelled = false, jobCancelled = false)
        )
    }

    @Test
    fun allFailed_isFailed() {
        assertEquals(
            JobStatus.FAILED,
            JobStatusResolver.resolve(anySuccess = false, anyFailure = true, anyCancelled = false, jobCancelled = false)
        )
    }

    @Test
    fun partialSuccessPartialFailure_isPartial_notCompleted() {
        // THE regression: previously resolved to COMPLETED.
        val status = JobStatusResolver.resolve(anySuccess = true, anyFailure = true, anyCancelled = false, jobCancelled = false)
        assertEquals(JobStatus.PARTIAL, status)
    }

    @Test
    fun partialSuccessPartialFailureNOT_completed() {
        val status = JobStatusResolver.resolve(anySuccess = true, anyFailure = true, anyCancelled = false, jobCancelled = false)
        assertNotEquals(JobStatus.COMPLETED, status)
    }

    @Test
    fun nothingButCancel_isCancelled() {
        assertEquals(
            JobStatus.CANCELLED,
            JobStatusResolver.resolve(anySuccess = false, anyFailure = false, anyCancelled = true, jobCancelled = false)
        )
    }

    @Test
    fun someSuccessSomeCancelled_isCompleted() {
        // Cancelling a subset of files (not a whole-job cancel) that all
        // completed otherwise is still "done" (best-effort batch).
        val status = JobStatusResolver.resolve(anySuccess = true, anyFailure = false, anyCancelled = true, jobCancelled = false)
        assertEquals(JobStatus.COMPLETED, status)
    }

    @Test
    fun jobCancelled_alwaysWins_overEverything() {
        // A whole-job cancel must win even if a file managed to succeed first.
        assertEquals(
            JobStatus.CANCELLED,
            JobStatusResolver.resolve(anySuccess = true, anyFailure = true, anyCancelled = true, jobCancelled = true)
        )
    }

    @Test
    fun emptyBatchNothingFlagged_isCompleted() {
        assertEquals(
            JobStatus.COMPLETED,
            JobStatusResolver.resolve(anySuccess = false, anyFailure = false, anyCancelled = false, jobCancelled = false)
        )
    }

    @Test
    fun allThreeFlagsFalseCombo_isCompleted() {
        // No success, no failure, no cancel, no job cancel -> the safe default.
        assertEquals(
            JobStatus.COMPLETED,
            JobStatusResolver.resolve(false, false, false, false)
        )
    }
}
