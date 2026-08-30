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

    // ---- bitrate correction -----------------------------------------------

    private fun bytesFor(bps: Int, sec: Int = seconds) = bps.toLong() * sec / 8

    @Test
    fun anOvershootingEncoderTriggersACorrectivePass() {
        // Planner asked for 2 Mbps, the hardware encoder delivered 4 Mbps.
        val corrected = VideoPlanner.correctedBitrate(2_000_000, bytesFor(4_000_000), 60_000)
        assertEquals(1_000_000, corrected)
    }

    @Test
    fun aFirstPassWithinToleranceIsLeftAlone() {
        assertEquals(
            null,
            VideoPlanner.correctedBitrate(2_000_000, bytesFor(2_100_000), 60_000)
        )
        assertEquals(
            null,
            VideoPlanner.correctedBitrate(2_000_000, bytesFor(1_200_000), 60_000)
        )
    }

    @Test
    fun correctionIsClampedSoOneWildPassCannotRuinTheNext() {
        // Encoder delivered 20x the target: proportional correction would ask
        // for 200 kbps, the clamp holds it at 45% of target.
        val corrected = VideoPlanner.correctedBitrate(2_000_000, bytesFor(40_000_000), 60_000)!!
        assertEquals(900_000, corrected)
    }

    @Test
    fun correctionNeverAsksForMoreThanTheTarget() {
        val corrected = VideoPlanner.correctedBitrate(2_000_000, bytesFor(2_400_000), 60_000)!!
        assertTrue("got $corrected", corrected < 2_000_000)
        assertTrue("got $corrected", corrected >= (2_000_000 * VideoPlanner.MIN_CORRECTION_RATIO).toInt())
    }

    @Test
    fun correctionIsSkippedWhenTheDurationIsUnknown() {
        assertEquals(null, VideoPlanner.correctedBitrate(2_000_000, bytesFor(4_000_000), 0))
        assertEquals(null, VideoPlanner.correctedBitrate(2_000_000, 0, 60_000))
        assertEquals(null, VideoPlanner.correctedBitrate(0, bytesFor(4_000_000), 60_000))
    }

    @Test
    fun measuredBitrateRoundTrips() {
        assertEquals(2_000_000, VideoPlanner.measuredBitrate(bytesFor(2_000_000), 60_000))
        assertEquals(0, VideoPlanner.measuredBitrate(0, 60_000))
    }

    // ---- audio track of a video -------------------------------------------

    @Test
    fun theAggressiveTiersCompressTheSoundtrack() {
        assertEquals(
            com.compressly.core.engine.model.VideoAudioMode.COMPRESS,
            PresetDefaults.videoDefaults[CompressionPreset.MAXIMUM_COMPRESSION]?.audioMode
        )
        assertEquals(
            com.compressly.core.engine.model.VideoAudioMode.COMPRESS,
            PresetDefaults.videoDefaults[CompressionPreset.HIGH_COMPRESSION]?.audioMode
        )
        // The quality tiers must not touch it.
        assertEquals(
            com.compressly.core.engine.model.VideoAudioMode.KEEP,
            PresetDefaults.videoDefaults[CompressionPreset.MAXIMUM_QUALITY]?.audioMode
        )
        assertEquals(
            com.compressly.core.engine.model.VideoAudioMode.KEEP,
            PresetDefaults.videoDefaults[CompressionPreset.SMART]?.audioMode
        )
    }

    @Test
    fun strippingAudioRemovesItFromTheEstimate() {
        val keep = VideoPlanner.audioBitrateBps(
            uhd, PresetDefaults.videoSettingsFor(CompressionPreset.BALANCED), CompressionPreset.BALANCED
        )
        val strip = VideoPlanner.audioBitrateBps(
            uhd,
            PresetDefaults.videoSettingsFor(CompressionPreset.BALANCED).copy(audioMode = VideoAudioMode.STRIP),
            CompressionPreset.BALANCED
        )
        assertTrue("keep=$keep strip=$strip", keep > 0 && strip == 0)
    }

    @Test
    fun compressedAudioNeverExceedsTheSourceTrack() {
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION)
        for (srcKbps in listOf(48, 64, 96, 128, 320)) {
            val info = messenger720.copy(audioBitrate = srcKbps * 1000)
            val got = VideoPlanner.audioBitrateBps(info, settings, CompressionPreset.MAXIMUM_COMPRESSION)
            assertTrue(
                "$srcKbps kbps source got a $got bps audio target",
                got <= srcKbps * 1000
            )
        }
    }

    @Test
    fun unknownAudioRateFallsBackToASafeDefault() {
        val got = VideoPlanner.audioBitrateBps(
            messenger720.copy(audioBitrate = 0),
            PresetDefaults.videoSettingsFor(CompressionPreset.BALANCED),
            CompressionPreset.BALANCED
        )
        assertEquals(VideoPlanner.DEFAULT_KEEP_AUDIO_BPS, got)
    }

    @Test
    fun switchingTierChangesTheAudioMode() {
        // It used to be carried over, so picking "maximum compression" after
        // "maximum quality" silently kept the full-rate soundtrack.
        val after = PresetDefaults.videoSettingsFor(
            CompressionPreset.MAXIMUM_COMPRESSION,
            PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_QUALITY)
        )
        assertEquals(com.compressly.core.engine.model.VideoAudioMode.COMPRESS, after.audioMode)
    }

    // ---- bits-per-pixel ceiling -------------------------------------------

    @Test
    fun everyTierHasABppCeiling() {
        for (preset in CompressionPreset.entries) {
            val bpp = PresetDefaults.videoDefaults[preset]?.bpp ?: 0.0
            assertTrue("$preset has no bpp ceiling", bpp > 0.0)
        }
    }

    @Test
    fun theCeilingSqueezesABloatedSource() {
        // 4K at 60 Mbps: the old source-share rule for MAXIMUM_COMPRESSION
        // produced ~1.3 Mbps, which is still enormous for a 720p output.
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION)
        val target = VideoPlanner.targetVideoBitrate(uhd, settings, CompressionPreset.MAXIMUM_COMPRESSION)
        val (w, h) = VideoPlanner.outputDims(uhd, settings, CompressionPreset.MAXIMUM_COMPRESSION)
        val ceiling = (w.toLong() * h * VideoPlanner.resolvedFps(settings, uhd) *
            PresetDefaults.videoDefaults[CompressionPreset.MAXIMUM_COMPRESSION]!!.bpp).toInt()
        assertTrue("target $target should sit at the ceiling $ceiling", target <= ceiling)
        assertTrue("target $target is not aggressive enough", target < 1_000_000)
    }

    @Test
    fun theCeilingOnlyEverMakesItMoreAggressive() {
        // Compare against the pre-ceiling rule: source share, pixel-scaled.
        for ((label, info) in sources) {
            for (preset in CompressionPreset.ordered) {
                val settings = PresetDefaults.videoSettingsFor(preset)
                val d = PresetDefaults.videoDefaults[preset]!!
                val (w, h) = VideoPlanner.outputDims(info, settings, preset)
                val srcArea = info.effectiveWidth.toLong() * info.effectiveHeight.coerceAtLeast(1)
                val outArea = w.toLong() * h
                var oldRule = VideoPlanner.effectiveSourceBitrate(info) * d.bitrateFactor
                if (srcArea > 0 && outArea < srcArea) oldRule *= outArea.toDouble() / srcArea
                val now = VideoPlanner.targetVideoBitrate(info, settings, preset)
                assertTrue(
                    "$label / $preset got $now but the old rule allowed ${oldRule.toInt()}",
                    now <= oldRule.toInt().coerceAtLeast(VideoPlanner.MIN_BITRATE)
                )
            }
        }
    }

    @Test
    fun theCeilingDoesNotOverCompressACleanSource() {
        // A 1080p clip already encoded at 2 Mbps should not be crushed to the
        // ceiling; the source-share term is lower and wins.
        val clean = fhd60Portrait.copy(videoBitrate = 2_000_000, audioBitrate = 0, frameRate = 30)
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_QUALITY)
        val target = VideoPlanner.targetVideoBitrate(clean, settings, CompressionPreset.MAXIMUM_QUALITY)
        assertTrue("target $target", target <= 2_000_000)
        assertTrue("target $target is needlessly harsh", target > 1_000_000)
    }

    @Test
    fun h265StillBeatsH264UnderTheCeiling() {
        val settings264 = PresetDefaults.videoSettingsFor(CompressionPreset.BALANCED)
        val settings265 = settings264.copy(codec = VideoCodec.H265)
        val a = VideoPlanner.targetVideoBitrate(uhd, settings264, CompressionPreset.BALANCED)
        val b = VideoPlanner.targetVideoBitrate(uhd, settings265, CompressionPreset.BALANCED)
        assertTrue("H265 $b should beat H264 $a", b < a)
    }

    @Test
    fun theEncoderIsNeverToldARateItCannotReceive() {
        // MAXIMUM_COMPRESSION asks for 30 fps; a 24 fps film cannot supply it.
        val film = fhd60Portrait.copy(frameRate = 24)
        val settings = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION)
        assertEquals(30, settings.frameRate)
        assertEquals(24, VideoPlanner.resolvedFps(settings, film))
        assertFalse("and no frames are dropped", VideoPlanner.dropsFrames(settings, film))
    }

    @Test
    fun anUnknownSourceRateHonoursTheRequest() {
        assertEquals(
            60,
            VideoPlanner.resolvedFps(VideoSettings(frameRate = 60), fhd60Portrait.copy(frameRate = 0))
        )
    }

    @Test
    fun theKeyFrameIntervalFollowsTheCorrectedRate() {
        val first = VideoPlanner.iFrameIntervalSeconds(4_000_000, 1280, 720)
        val corrected = VideoPlanner.iFrameIntervalSeconds(600_000, 1280, 720)
        assertTrue("first=$first corrected=$corrected", corrected > first)
    }

    @Test
    fun shortClipsAreNotReEncodedOnMeasurementNoise() {
        // A 1 s clip carries the same container overhead as a long one, so
        // bytes/duration overstates its rate. Correcting on that is wasted work.
        assertEquals(
            null,
            VideoPlanner.correctedBitrate(2_000_000, bytesFor(6_000_000, 1), 1_000)
        )
        // ...and a long clip with a moderate overshoot still gets corrected
        // proportionally (4 Mbps against a 2 Mbps target -> 1 Mbps).
        assertEquals(
            1_000_000,
            VideoPlanner.correctedBitrate(2_000_000, bytesFor(4_000_000), 60_000)
        )
        // A 6x overshoot would ask for 333 kbps; the clamp holds it at 45%.
        assertEquals(
            900_000,
            VideoPlanner.correctedBitrate(2_000_000, bytesFor(6_000_000), 60_000)
        )
    }

    @Test
    fun aTinyOutputIsNotReEncoded() {
        assertEquals(null, VideoPlanner.correctedBitrate(2_000_000, 10_000, 60_000))
    }

    // ---- frame dropping accuracy ------------------------------------------

    @Test
    fun aNonIntegerRateChangeLandsNearTheRequestedRate() {
        // 30 fps source asked for 24. Measuring the gap from the last KEPT
        // frame dropped every other frame and delivered ~15 fps.
        val gate = VideoPlanner.FrameGate((1_000_000.0 / 24).toLong())
        val kept = gate.keptOutOf(sourceFps = 30, targetFps = 24, count = 300)
        val effectiveFps = kept * 30.0 / 300
        assertTrue("got $effectiveFps fps from a 30 fps source", effectiveFps in 23.0..26.0)
    }

    @Test
    fun halvingTheRateKeepsExactlyHalf() {
        val gate = VideoPlanner.FrameGate((1_000_000.0 / 30).toLong())
        val kept = gate.keptOutOf(sourceFps = 60, targetFps = 30, count = 600)
        assertEquals(300, kept)
    }

    @Test
    fun theFirstFrameIsAlwaysKept() {
        val gate = VideoPlanner.FrameGate(40_000)
        assertTrue(gate.shouldKeep(0))
        assertFalse(gate.shouldKeep(10_000))
        assertTrue(gate.shouldKeep(45_000))
    }

    @Test
    fun anIrregularGapDoesNotReleaseABurst() {
        val gate = VideoPlanner.FrameGate(40_000)
        assertTrue(gate.shouldKeep(0))
        // A half-second hole in the source, then frames resume densely. The
        // schedule catches up to 520_000, so the frames packed in behind the
        // gap are dropped instead of being released as a burst.
        assertTrue(gate.shouldKeep(500_000))
        assertFalse("the schedule must have caught up", gate.shouldKeep(505_000))
        assertFalse(gate.shouldKeep(510_000))
        assertFalse(gate.shouldKeep(515_000))
        assertTrue(gate.shouldKeep(525_000))
        assertFalse(gate.shouldKeep(530_000))
    }
}
