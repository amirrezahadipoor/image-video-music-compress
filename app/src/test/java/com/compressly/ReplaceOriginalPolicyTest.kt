package com.compressly

import com.compressly.core.engine.InPlace
import com.compressly.core.engine.decideInPlace
import com.compressly.core.util.JobTotals
import org.junit.Test

import org.junit.Assert.assertEquals

/**
 * The two rules behind the "batch photos were compressed but the originals are
 * all still there" report.
 *
 * Both used to be decided in the engine at write time, next to a ContentResolver
 * call, which made them invisible to the permission prompt on the settings screen
 * and impossible to test without a phone. They are pure functions now, so CI can
 * hold them to what the UI promises.
 */
class ReplaceOriginalPolicyTest {

    // ── which write path a source row takes ──────────────────────────────

    @Test
    fun sameFormatOverwritesInPlace() {
        assertEquals(InPlace.OVERWRITE, decideInPlace("image/jpeg", "image/jpeg"))
        assertEquals(InPlace.OVERWRITE, decideInPlace("image/heic", "image/heic"))
    }

    @Test
    fun crossFormatRetypesTheRowInsteadOfDuplicatingTheFile() {
        // HEIC in, JPEG out was the common case that produced duplicates: the
        // engine refused to write across formats, published a NEW row, and then
        // Android refused to delete the original it did not own.
        assertEquals(InPlace.RETYPE_THEN_OVERWRITE, decideInPlace("image/heic", "image/jpeg"))
        assertEquals(InPlace.RETYPE_THEN_OVERWRITE, decideInPlace("image/png", "image/jpeg"))
        assertEquals(InPlace.RETYPE_THEN_OVERWRITE, decideInPlace("image/webp", "image/jpeg"))
        assertEquals(InPlace.RETYPE_THEN_OVERWRITE, decideInPlace("video/hevc", "video/mp4"))
    }

    @Test
    fun unknownSourceMimeIsNeverWrittenOver() {
        // Guessing here would put JPEG bytes into a .heic name.
        assertEquals(InPlace.PUBLISH, decideInPlace(null, "image/jpeg"))
    }

    // ── what the batch is expected to weigh afterwards ───────────────────

    @Test
    fun batchEstimateUsesTheMeasuredRatioNotFirstTimesN() {
        // One 60 MB clip analysed at the front, 40 phone clips of 8 MB behind it.
        // The first compressed to 6 MB, so the real folder outcome is roughly
        // 40 x 8 MB x 0.1 = 32 MB — not 6 MB x 41 = 246 MB.
        val total = JobTotals.estimateBatchBytes(
            totalOriginal = 40L * 8_000_000L,
            firstOriginal = 60_000_000L,
            firstEstimate = 6_000_000L,
            count = 41
        )
        assertEquals(32_000_000L, total)
    }

    @Test
    fun singleFileEstimateIsTakenAsIs() {
        assertEquals(6_000_000L, JobTotals.estimateBatchBytes(60_000_000L, 60_000_000L, 6_000_000L, 1))
    }

    @Test
    fun withoutARatioItFallsBackToTimesN() {
        // Sizes unknown (a failed MediaStore query): the old rule is still the
        // safest guess, and it errs towards warning rather than towards a job that
        // dies mid-way on a full volume.
        assertEquals(
            240_000_000L,
            JobTotals.estimateBatchBytes(totalOriginal = 0L, firstOriginal = 0L, firstEstimate = 6_000_000L, count = 40)
        )
        assertEquals(
            0L,
            JobTotals.estimateBatchBytes(totalOriginal = 320_000_000L, firstOriginal = 0L, firstEstimate = 0L, count = 40)
        )
    }
}
