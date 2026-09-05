package com.compressly

import com.compressly.core.data.OutputStore
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.VideoResolution
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.video.VideoPlanner
import com.compressly.core.util.JobTotals
import com.compressly.core.util.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths and naming rules behind what the user is told after a job.
 *
 * Every case here is a defect that was reported from a real device: a result
 * screen whose totals did not belong to the file on it, a batch that was
 * presented as a saving while it grew, a name MediaStore then renamed to
 * "IMG_1 (1).jpg", and a custom frame size an encoder would reject. None of it
 * needs a device to check — all four rules are pure functions now, so they run
 * in the ordinary JVM test task the CI gate uses.
 */
class ResultMathTest {

    private var seq = 0L

    private fun row(
        status: String,
        inputSize: Long,
        outputSize: Long,
        error: String? = null,
        sameUri: Boolean = false
    ): HistoryEntry {
        seq += 1
        val id = seq
        val input = "content://media/external/images/media/$id"
        return HistoryEntry(
            id = id,
            jobId = 7L,
            mediaType = "PHOTO",
            fileName = "IMG_$id.jpg",
            inputUri = input,
            inputSize = inputSize,
            outputUri = when {
                status != HistoryEntry.STATUS_DONE -> null
                sameUri -> input
                else -> "content://media/external/images/media/out_$id"
            },
            outputSize = outputSize,
            status = status,
            error = error,
            settingsSummary = "",
            createdAt = 0L,
            durationMs = 0L
        )
    }

    // ---- what a job's totals are allowed to include -----------------------

    @Test
    fun onlyFinishedRowsFeedTheByteMaths() {
        val totals = JobTotals.of(
            listOf(
                row(HistoryEntry.STATUS_DONE, 1_000, 400),
                row(HistoryEntry.STATUS_DONE, 1_000, 600),
                row(HistoryEntry.STATUS_FAILED, 5_000, 0),
                row(HistoryEntry.STATUS_CANCELLED, 9_000, 0)
            )
        )
        assertEquals(2_000L, totals.before)
        assertEquals(1_000L, totals.after)
        assertEquals(1_000L, totals.saved)
        assertEquals(0.5, totals.reduction, 1e-9)
        // Failures stay visible in the count line, they just cannot contribute
        // their (absent) output size to the average.
        assertEquals(2, totals.done)
        assertEquals(4, totals.total)
    }

    @Test
    fun aBatchThatGrewIsNeverReportedAsASaving() {
        val totals = JobTotals.of(listOf(row(HistoryEntry.STATUS_DONE, 1_000, 1_200)))
        assertEquals(-200L, totals.saved)
        assertTrue("a growing batch must be flagged as grew", totals.grew)
        assertEquals(0.0, totals.reduction, 1e-9)
    }

    @Test
    fun perFileAndWholeSetAgreeWhenNothingGrows() {
        val rows = listOf(
            row(HistoryEntry.STATUS_DONE, 4_000, 1_000),
            row(HistoryEntry.STATUS_DONE, 6_000, 2_000)
        )
        val totals = JobTotals.of(rows)
        assertEquals(rows.sumOf { it.savedBytes }, totals.saved)
    }

    @Test
    fun retainedOriginalsAreCountedOnSuccessfulRowsOnly() {
        val retained = row(HistoryEntry.STATUS_DONE, 1_000, 500, error = HistoryEntry.ERROR_ORIGINAL_RETAINED)
        assertTrue("the marker must be readable off the row", retained.originalRetained)
        val totals = JobTotals.of(
            listOf(
                retained,
                row(HistoryEntry.STATUS_DONE, 1_000, 500),
                // The same string on a failed row is its real error key, never a
                // "retained" claim.
                row(HistoryEntry.STATUS_FAILED, 1_000, 0, error = HistoryEntry.ERROR_ORIGINAL_RETAINED)
            )
        )
        assertEquals(1, totals.retained)
    }

    @Test
    fun anInPlaceReplacementIsNotARetainedOriginal() {
        val e = row(HistoryEntry.STATUS_DONE, 1_000, 500, sameUri = true)
        assertNotEquals(HistoryEntry.ERROR_ORIGINAL_RETAINED, e.error)
        assertFalse(e.originalRetained)
    }

    // ---- published file names ---------------------------------------------

    @Test
    fun theNameCarriesTheContainerThatWasActuallyWritten() {
        assertTrue(OutputStore.uniqueNameFor("clip.mov", "video/mp4").endsWith(".mp4"))
        assertTrue(OutputStore.uniqueNameFor("a.heic", "image/jpeg").endsWith(".jpg"))
        assertTrue(OutputStore.uniqueNameFor("song.m4a", "audio/mpeg").endsWith(".mp3"))
        assertTrue(OutputStore.uniqueNameFor("song.m4a", "audio/mp4").endsWith(".m4a"))
    }

    @Test
    fun theStemIsKeptSoTheUserStillFindsTheirFile() {
        val name = OutputStore.uniqueNameFor("IMG_2048.png", "image/jpeg")
        assertTrue("unexpected name $name", name.startsWith("IMG_2048_compressed_"))
    }

    @Test
    fun noTwoFilesInABatchShareAName() {
        // The bug this guards: a second-granularity stamp made every file of a
        // fast batch identical, and MediaStore renamed them all to " (1)", " (2)".
        val names = (1..3_000).map { OutputStore.uniqueNameFor("IMG_0001.jpg", "image/jpeg") }.toSet()
        assertEquals(3_000, names.size)
    }

    @Test
    fun mimeExtensionTableCoversEveryEngineOutput() {
        assertEquals(".jpg", Mime.extensionFor("image/jpeg"))
        assertEquals(".png", Mime.extensionFor("image/png"))
        assertEquals(".webp", Mime.extensionFor("image/webp"))
        assertEquals(".mp4", Mime.extensionFor("video/mp4"))
        assertEquals(".mp3", Mime.extensionFor("audio/mpeg"))
        assertEquals(".m4a", Mime.extensionFor("audio/mp4"))
        assertEquals(".wav", Mime.extensionFor("audio/x-wav"))
        assertEquals("", Mime.extensionFor("application/octet-stream"))
        assertEquals("", Mime.extensionFor(null))
    }

    // ---- custom video frame sizes -----------------------------------------

    @Test
    fun customEdgesAreClampedAndMadeEven() {
        assertEquals(1080, VideoPlanner.encoderSize(1080))
        // JUnit's overload is assertEquals(message, expected, actual).
        assertEquals("odd edges are rejected by H.264 encoders", 1080, VideoPlanner.encoderSize(1081))
        assertEquals("a 1 px field must not reach the encoder", 64, VideoPlanner.encoderSize(1))
        assertEquals(8000, VideoPlanner.encoderSize(99_999))
        assertEquals(7998, VideoPlanner.encoderSize(7999))
    }

    @Test
    fun customResolutionIsWhatTheEncoderIsConfiguredWith() {
        val info = MediaInfo(width = 1920, height = 1080, frameRate = 30, durationMs = 60_000)
        val settings = VideoSettings(
            resolution = VideoResolution.CUSTOM,
            customWidth = 641,
            customHeight = 361
        )
        val (w, h) = VideoPlanner.outputDims(info, settings, CompressionPreset.BALANCED)
        assertEquals(640, w)
        assertEquals(360, h)
    }
}
