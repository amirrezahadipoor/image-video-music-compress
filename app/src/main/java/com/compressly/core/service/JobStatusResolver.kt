package com.compressly.core.service

import com.compressly.core.engine.model.JobStatus

/**
 * Pure mapping from the per-item outcome flags to the whole-job terminal
 * status. Extracted from JobCoordinator.runJob() so the exact precedence rules
 * are pinned by JVM tests (no android.* imports).
 *
 * Precedence (highest first):
 *  1. Whole-job cancel -> CANCELLED
 *  2. Nothing succeeded and something failed -> FAILED
 *  3. Nothing succeeded and something was cancelled -> CANCELLED
 *  4. Some succeeded AND some failed -> PARTIAL   (a mixed batch is not "done")
 *  5. Otherwise (all succeeded / all cancelled-file / nothing failed) -> COMPLETED
 */
object JobStatusResolver {

    fun resolve(
        anySuccess: Boolean,
        anyFailure: Boolean,
        anyCancelled: Boolean,
        jobCancelled: Boolean
    ): JobStatus = when {
        jobCancelled -> JobStatus.CANCELLED
        !anySuccess && anyFailure -> JobStatus.FAILED
        !anySuccess && anyCancelled -> JobStatus.CANCELLED
        anyFailure -> JobStatus.PARTIAL
        else -> JobStatus.COMPLETED
    }
}
