package com.compressly

import com.compressly.core.engine.estimate.GradeAdvisor
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeAdvisorTest {

    private val uhd = MediaInfo(
        width = 3840, height = 2160, frameRate = 30, durationMs = 60_000,
        videoBitrate = 60_000_000, audioBitrate = 128_000, hasVideo = true, hasAudio = true
    )
    private val phone1080 = MediaInfo(
        width = 1080, height = 1920, rotation = 90, frameRate = 60, durationMs = 60_000,
        videoBitrate = 16_000_000, audioBitrate = 128_000, hasVideo = true, hasAudio = true
    )
    private val messenger720 = MediaInfo(
        width = 1280, height = 720, frameRate = 30, durationMs = 60_000,
        videoBitrate = 1_200_000, audioBitrate = 64_000, hasVideo = true, hasAudio = true
    )
    private val tiny480 = MediaInfo(
        width = 854, height = 480, frameRate = 25, durationMs = 60_000,
        videoBitrate = 200_000, audioBitrate = 48_000, hasVideo = true, hasAudio = true
    )

    @Test
    fun fourKGetsMaximumOrHigh() {
        val snap = GradeAdvisor.advise(MediaType.VIDEO, uhd, 60_000_000L * 60 / 8)
        assertTrue(
            "4K suggested ${snap.recommended}",
            snap.recommended == CompressionPreset.MAXIMUM_COMPRESSION ||
                snap.recommended == CompressionPreset.HIGH_COMPRESSION
        )
    }

    @Test
    fun bloatedPhoneClipGetsHigh() {
        val snap = GradeAdvisor.advise(MediaType.VIDEO, phone1080, 16_000_000L * 60 / 8)
        assertEquals(CompressionPreset.HIGH_COMPRESSION, snap.recommended)
    }

    @Test
    fun tinyClipIsNotCrushedByDefault() {
        val snap = GradeAdvisor.advise(MediaType.VIDEO, tiny480, 200_000L * 60 / 8)
        assertEquals(CompressionPreset.MAXIMUM_QUALITY, snap.recommended)
    }

    @Test
    fun everyGradeHasAnEstimate() {
        val snap = GradeAdvisor.advise(MediaType.VIDEO, phone1080, 16_000_000L * 60 / 8)
        assertEquals(4, snap.estimates.size)
        for (p in CompressionPreset.ordered) {
            assertTrue("$p missing", (snap.estimates[p] ?: 0) > 0)
        }
    }

    @Test
    fun strongerGradesEstimateSmallerOnBloatedVideo() {
        val snap = GradeAdvisor.advise(MediaType.VIDEO, uhd, 60_000_000L * 60 / 8)
        val q = snap.estimates[CompressionPreset.MAXIMUM_QUALITY]!!
        val b = snap.estimates[CompressionPreset.BALANCED]!!
        val h = snap.estimates[CompressionPreset.HIGH_COMPRESSION]!!
        val m = snap.estimates[CompressionPreset.MAXIMUM_COMPRESSION]!!
        assertTrue("quality $q balanced $b", q > b)
        assertTrue("balanced $b high $h", b > h)
        assertTrue("high $h max $m", h > m)
    }

    @Test
    fun maxSavesMoreThanHalfOnFourK() {
        val original = 60_000_000L * 60 / 8
        val snap = GradeAdvisor.advise(MediaType.VIDEO, uhd, original)
        val max = snap.estimates[CompressionPreset.MAXIMUM_COMPRESSION]!!
        assertTrue("max $max of $original", GradeAdvisor.savingFraction(original, max) >= 0.70)
    }

    @Test
    fun voiceMemoIsNotRecommendedMaximum() {
        val info = MediaInfo(durationMs = 60_000, audioBitrate = 64_000, hasAudio = true)
        val snap = GradeAdvisor.advise(MediaType.AUDIO, info, 64_000L * 60 / 8)
        assertEquals(CompressionPreset.MAXIMUM_QUALITY, snap.recommended)
    }

    @Test
    fun largePhotoSuggestsRealCompression() {
        val info = MediaInfo(width = 4000, height = 3000)
        val snap = GradeAdvisor.advise(MediaType.PHOTO, info, 9_000_000, "image/jpeg")
        assertTrue(
            "photo suggested ${snap.recommended}",
            snap.recommended == CompressionPreset.HIGH_COMPRESSION ||
                snap.recommended == CompressionPreset.BALANCED
        )
    }

    @Test
    fun savingIsZeroWhenEstimateExceedsOriginal() {
        assertEquals(0.0, GradeAdvisor.savingFraction(1000, 2000), 0.0)
        assertEquals(0.5, GradeAdvisor.savingFraction(1000, 500), 0.0)
    }

    @Test
    fun messengerClipStillGetsAUsableSuggestion() {
        val original = 1_200_000L * 60 / 8
        val snap = GradeAdvisor.advise(MediaType.VIDEO, messenger720, original)
        assertTrue(snap.recommended in CompressionPreset.ordered)
        val est = snap.estimates[snap.recommended]!!
        assertTrue(est > 0)
    }
}
