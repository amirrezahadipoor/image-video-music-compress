package com.compressly

import com.compressly.core.engine.estimate.SizeEstimator
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.core.engine.model.VideoAudioMode
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.video.VideoPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the video planning rules. Every case here is a real file shape that
 * used to come out of the engine wrong: Smart and Maximum-Compression made
 * already-small clips *bigger*, Smart priced 4K footage it never wrote, and the
 * encoder was told 30 fps whatever the source actually was.
 */
class VideoPlannerTest {

    private val seconds = 60

    private fun MediaInfo.bytes() = videoBitrate.toLong() * seconds / 8

    private val uhd = MediaInfo(
        width = 3840, height = 2160, frameRate = 30, durationMs = 60_000,
        videoBitrate = 60_000_000, audioBitrate = 128_000, hasVideo = true, hasAudio = true
    )
    private val fhd60Portrait = MediaInfo(
        width = 1080, height = 1920, rotation = 90, frameRate = 60, durationMs = 60_000,
        videoBitrate = 20_000_000, audioBitrate = 128_000, hasVideo = true, hasAudio = true
    )
    private val messenger720 = MediaInfo(
        width = 1280, height = 720, frameRate = 30, durationMs = 60_000,
        videoBitrate = 1_200_000, audioBitrate = 64_000, hasVideo = true, hasAudio = true
    )
    private val tiny480 = MediaInfo(
        width = 854, height = 480, frameRate = 25, durationMs = 60_000,
        videoBitrate = 200_000, audioBitrate = 48_000, hasVideo = true, hasAudio = true
    )

    private val sources = listOf(
        "4K" to uhd,
        "1080p60 portrait" to fhd60Portrait,
        "720p messenger" to messenger720,
        "480p tiny" to tiny480
    )

    // ---- the headline regression -----------------------------------------

    @Test
    fun noPresetEverProducesABiggerFile() {
        for ((label, info) in sources) {
            for (preset in CompressionPreset.entries) {
                val settings = PresetDefaults.videoSettingsFor(preset)
                val estimated = SizeEstimator.estimateVideo(info, settings, preset)
                val input = info.bytes()
                assertTrue(
                    "$label / $preset estimated $estimated B from a $input B source " +
                        "and was not flagged as keep-original",
                    estimated < input || VideoPlanner.shouldKeepOriginal(estimated, input)
                )
            }
        }
    }

    @Test
    fun smartOnAlreadySmallClipIsFlaggedAsNoGain() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        val estimated = SizeEstimator.estimateVideo(messenger720, settings, CompressionPreset.SMART)
        assertTrue(
            "a 1.2 Mbps clip should not be re-encoded for a 3 % saving",
            VideoPlanner.shouldKeepOriginal(estimated, messenger720.bytes())
        )
    }

    @Test
    fun aRealReductionIsNotFlagged() {
        assertFalse(
            VideoPlanner.shouldKeepOriginal(messenger720.bytes() / 4, messenger720.bytes())
        )
    }

    // ---- the source rate is the ceiling ----------------------------------

    @Test
    fun targetNeverExceedsTheSourceVideoRate() {
        for ((label, info) in sources) {
            val source = VideoPlanner.sourceVideoBitrate(info)
            if (source <= 0) continue
            for (preset in CompressionPreset.entries) {
                val target = VideoPlanner.targetVideoBitrate(
                    info, PresetDefaults.videoSettingsFor(preset), preset
                )
                assertTrue(
                    "$label / $preset target $target exceeded source $source",
                    target <= source
                )
            }
        }
    }

    @Test
    fun containerRateHasItsAudioTrackSubtracted() {
        // 1.200 Mbps container - 64 kbps audio = 1.136 Mbps of video.
        assertEquals(1_136_000, VideoPlanner.sourceVideoBitrate(messenger720))
    }

    @Test
    fun missingContainerRateFallsBackToAHeuristic() {
        assertTrue(VideoPlanner.effectiveSourceBitrate(messenger720.copy(videoBitrate = 0)) > 0)
        assertEquals(0, VideoPlanner.sourceVideoBitrate(messenger720.copy(videoBitrate = 0)))
    }

    @Test
    fun sourceThinnerThanTheEncoderFloorIsReturnedUntouched() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION)
        assertEquals(
            VideoPlanner.sourceVideoBitrate(tiny480),
            VideoPlanner.targetVideoBitrate(tiny480, settings, CompressionPreset.MAXIMUM_COMPRESSION)
        )
    }

    @Test
    fun explicitManualBitrateAlwaysWins() {
        val settings = VideoSettings(bitrate = 3_000_000)
        assertEquals(
            3_000_000,
            VideoPlanner.targetVideoBitrate(uhd, settings, CompressionPreset.SMART)
        )
    }

    // ---- Smart prices what it actually writes ----------------------------

    @Test
    fun smartCapsFootageOnItsLongEdge() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        val (w, h) = VideoPlanner.outputDims(uhd, settings, CompressionPreset.SMART)
        assertTrue("4K Smart output ${w}x$h should be capped", maxOf(w, h) <= VideoPlanner.SMART_MAX_EDGE)
    }

    @Test
    fun smartBitrateMatchesTheOutputResolutionNotTheSource() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        val target = VideoPlanner.targetVideoBitrate(uhd, settings, CompressionPreset.SMART)
        // 1920x1072 @ 30fps @ 0.085 bpp ~= 5.2 Mbps. The old code priced the
        // 4K source and hit the 16 Mbps ceiling, so Smart barely compressed.
        assertTrue("Smart 4K target was $target, expected roughly 1080p pricing",
            target in 4_000_000..7_000_000)
    }

    // ---- frame rate -------------------------------------------------------

    @Test
    fun encoderFollowsTheSourceFrameRate() {
        assertEquals(
            60,
            VideoPlanner.resolvedFps(PresetDefaults.videoSettingsFor(CompressionPreset.SMART), fhd60Portrait)
        )
        assertEquals(
            30,
            VideoPlanner.resolvedFps(VideoSettings(), fhd60Portrait.copy(frameRate = 30))
        )
        // Unknown source rate falls back to 30 rather than 0.
        assertEquals(30, VideoPlanner.resolvedFps(VideoSettings(), fhd60Portrait.copy(frameRate = 0)))
    }

    @Test
    fun explicitUserFrameRateOverridesTheSource() {
        assertEquals(
            24,
            VideoPlanner.resolvedFps(VideoSettings(frameRate = 24), fhd60Portrait)
        )
    }

    @Test
    fun framesAreOnlyDroppedWhenTheRequestIsBelowTheSource() {
        assertFalse(VideoPlanner.dropsFrames(PresetDefaults.videoSettingsFor(CompressionPreset.SMART), fhd60Portrait))
        assertTrue(VideoPlanner.dropsFrames(PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION), fhd60Portrait))
        assertFalse(
            "asking for 60 fps on a 30 fps clip cannot invent frames",
            VideoPlanner.dropsFrames(VideoSettings(frameRate = 60), fhd60Portrait.copy(frameRate = 30))
        )
    }

    @Test
    fun smartPricedAtTheRealFrameRate() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        val at60 = VideoPlanner.targetVideoBitrate(fhd60Portrait, settings, CompressionPreset.SMART)
        val at30 = VideoPlanner.targetVideoBitrate(
            fhd60Portrait.copy(frameRate = 30), settings, CompressionPreset.SMART
        )
        assertTrue("60 fps needs more bits than 30 fps ($at60 vs $at30)", at60 > at30)
    }

    // ---- dimensions -------------------------------------------------------

    @Test
    fun resolutionTiersAreOrientationAware() {
        val portrait = messenger720.copy(width = 1080, height = 1920, rotation = 0)
        val (w, h) = VideoPlanner.outputDims(
            portrait,
            PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION),
            CompressionPreset.MAXIMUM_COMPRESSION
        )
        // The old code compared against a landscape 1280x720 box and squashed
        // this to 400x720.
        assertEquals(720 to 1280, w to h)
    }

    @Test
    fun dimensionsAreAlignedTo16() {
        for ((_, info) in sources) {
            for (preset in CompressionPreset.entries) {
                val (w, h) = VideoPlanner.outputDims(info, PresetDefaults.videoSettingsFor(preset), preset)
                assertEquals("$info/$preset width $w", 0, w % 16)
                assertEquals("$info/$preset height $h", 0, h % 16)
            }
        }
    }

    @Test
    fun unknownSourceDimensionsStillYieldAUsableSize() {
        val (w, h) = VideoPlanner.outputDims(
            MediaInfo(), VideoSettings(), CompressionPreset.SMART
        )
        assertTrue(w >= 16 && h >= 16)
    }

    // ---- codec ------------------------------------------------------------

    @Test
    fun h265TargetIsSmallerThanH264() {
        val h264 = VideoPlanner.targetVideoBitrate(
            uhd, VideoSettings(codec = VideoCodec.H264), CompressionPreset.BALANCED
        )
        val h265 = VideoPlanner.targetVideoBitrate(
            uhd, VideoSettings(codec = VideoCodec.H265), CompressionPreset.BALANCED
        )
        assertTrue("H.265 ($h265) should beat H.264 ($h264)", h265 < h264)
    }

    // ---- tier ordering ----------------------------------------------------

    @Test
    fun moreCompressionAlwaysMeansSmaller() {
        for ((label, info) in listOf("4K" to uhd, "1080p60" to fhd60Portrait)) {
            val sizes = CompressionPreset.ordered.map {
                SizeEstimator.estimateVideo(info, PresetDefaults.videoSettingsFor(it), it)
            }
            assertTrue("$label sizes not monotonically decreasing: $sizes",
                sizes[0] > sizes[1] && sizes[1] > sizes[2] && sizes[2] > sizes[3])
        }
    }

    // ---- key frames -------------------------------------------------------

    @Test
    fun keyFrameIntervalWidensAtVeryLowBitrates() {
        assertTrue(VideoPlanner.iFrameIntervalSeconds(250_000, 1280, 720) >= 4)
        assertEquals(2, VideoPlanner.iFrameIntervalSeconds(5_000_000, 1920, 1080))
    }

    // ---- audio ------------------------------------------------------------

    @Test
    fun strippingAudioStillEstimatesSmaller() {
        val keep = SizeEstimator.estimateVideo(uhd, VideoSettings(), CompressionPreset.BALANCED)
        val strip = SizeEstimator.estimateVideo(
            uhd, VideoSettings(audioMode = VideoAudioMode.STRIP), CompressionPreset.BALANCED
        )
        assertTrue(strip < keep)
    }

    // ---- "leave the file alone" decision ----------------------------------

    @Test
    fun smartOnAnUnchangedSmallClipIsANoOp() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        assertTrue(
            "same resolution, same rate, audio kept, no trim => nothing to do",
            VideoPlanner.isNoOpTranscode(messenger720, settings, CompressionPreset.SMART)
        )
    }

    @Test
    fun anExplicitChangeIsNeverTreatedAsANoOp() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        assertFalse(VideoPlanner.isNoOpTranscode(messenger720, settings.copy(trimEnabled = true), CompressionPreset.SMART))
        assertFalse(VideoPlanner.isNoOpTranscode(messenger720, settings.copy(audioMode = VideoAudioMode.STRIP), CompressionPreset.SMART))
        assertFalse(VideoPlanner.isNoOpTranscode(
            messenger720,
            settings.copy(resolution = com.compressly.core.engine.model.VideoResolution.R480),
            CompressionPreset.SMART
        ))
        assertFalse(VideoPlanner.isNoOpTranscode(messenger720, settings.copy(frameRate = 15), CompressionPreset.SMART))
    }

    @Test
    fun smartDownscalingLargeFootageIsNotANoOp() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        assertFalse(
            "Smart still resizes 4K down to 1920, so it must transcode",
            VideoPlanner.isNoOpTranscode(uhd, settings, CompressionPreset.SMART)
        )
    }
}
