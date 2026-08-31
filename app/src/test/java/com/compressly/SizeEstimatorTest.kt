package com.compressly

import com.compressly.core.engine.estimate.SizeEstimator
import com.compressly.core.engine.model.AudioBitrateMode
import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeEstimatorTest {

    @Test
    fun photoEstimate_qualityMonotonic() {
        val hi = SizeEstimator.estimatePhoto(
            "image/jpeg", 4000, 3000, 4_000_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 95)
        )
        val lo = SizeEstimator.estimatePhoto(
            "image/jpeg", 4000, 3000, 4_000_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 40)
        )
        assertTrue("quality 95 should estimate larger than 40", hi > lo)
    }

    @Test
    fun photoEstimate_pngBiggerThanJpeg() {
        val jpeg = SizeEstimator.estimatePhoto(
            "image/jpeg", 1920, 1080, 1_000_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 80)
        )
        val png = SizeEstimator.estimatePhoto(
            "image/jpeg", 1920, 1080, 1_000_000,
            PhotoSettings(outputFormat = PhotoFormat.PNG, quality = 80)
        )
        assertTrue("PNG should estimate larger than JPEG", png > jpeg)
    }

    @Test
    fun photoEstimate_resizeSmaller() {
        val none = SizeEstimator.estimatePhoto(
            "image/jpeg", 6000, 4000, 9_000_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 80, resize = PhotoResize.NONE)
        )
        val r1024 = SizeEstimator.estimatePhoto(
            "image/jpeg", 6000, 4000, 9_000_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 80, resize = PhotoResize.R1024)
        )
        assertTrue("resize should shrink estimate", r1024 < none)
    }

    @Test
    fun photoEstimate_neverExceedsOriginalWhenKnown() {
        val original = 200_000L
        val e = SizeEstimator.estimatePhoto(
            "image/png", 4000, 3000, original,
            PhotoSettings(outputFormat = PhotoFormat.PNG, quality = 100)
        )
        assertTrue("estimate $e vs original $original", e <= original)
    }

    @Test
    fun photoEstimate_alwaysPositive() {
        val e = SizeEstimator.estimatePhoto(
            "image/jpeg", 640, 480, 80_000,
            PhotoSettings(outputFormat = PhotoFormat.JPEG, quality = 100)
        )
        assertTrue(e > 0)
    }

    private val hd = MediaInfo(
        width = 1920, height = 1080, durationMs = 60_000,
        videoBitrate = 8_000_000, audioBitrate = 128_000,
        hasVideo = true, hasAudio = true
    )

    @Test
    fun videoEstimate_saneRange() {
        val e = SizeEstimator.estimateVideo(hd, VideoSettings(), CompressionPreset.BALANCED)
        // 60s at ~4.8 Mbps + 128k audio ~ 37 MB; keep a generous sanity band.
        assertTrue("video estimate should be positive", e > 100_000)
        assertTrue("video estimate should be < 200MB for 60s", e < 200_000_000)
    }

    @Test
    fun videoEstimate_stripAudioSmallerThanKeep() {
        val keep = SizeEstimator.estimateVideo(
            hd, VideoSettings(), CompressionPreset.BALANCED
        )
        val strip = SizeEstimator.estimateVideo(
            hd,
            VideoSettings(audioMode = com.compressly.core.engine.model.VideoAudioMode.STRIP),
            CompressionPreset.BALANCED
        )
        assertTrue("stripping audio should shrink estimate", strip < keep)
    }

    @Test
    fun smartVideoBitrate_bounds() {
        val b = SizeEstimator.smartVideoBitrate(1920, 1080, 30, VideoCodec.H264)
        assertTrue("smart bitrate >= 500k", b >= 500_000)
        assertTrue("smart bitrate <= 16M", b <= 16_000_000)
        val bH265 = SizeEstimator.smartVideoBitrate(1920, 1080, 30, VideoCodec.H265)
        assertTrue("H265 should be smaller than H264", bH265 < b)
    }

    @Test
    fun smartVideoBitrate_scalesWithPixels() {
        val small = SizeEstimator.smartVideoBitrate(640, 360, 30, VideoCodec.H264)
        val big = SizeEstimator.smartVideoBitrate(3840, 2160, 30, VideoCodec.H264)
        assertTrue("bigger resolution -> bigger smart bitrate", big > small)
    }

    @Test
    fun audioEstimate_matchesBitrate() {
        // 60s at 128 kbps = 128_000 * 60 / 8 = 960_000 bytes
        val e = SizeEstimator.estimateAudio(
            60_000,
            AudioSettings(bitrate = 128, bitrateMode = AudioBitrateMode.CBR)
        )
        assertTrue("CBR estimate ~960KB", e in 850_000..1_100_000)
    }

    @Test
    fun audioEstimate_vbrSlightlySmaller() {
        val cbr = SizeEstimator.estimateAudio(60_000, AudioSettings(bitrate = 192, bitrateMode = AudioBitrateMode.CBR))
        val vbr = SizeEstimator.estimateAudio(60_000, AudioSettings(bitrate = 192, bitrateMode = AudioBitrateMode.VBR))
        assertTrue("VBR estimate <= CBR", vbr <= cbr)
    }
}
