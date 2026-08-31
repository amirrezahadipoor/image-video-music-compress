package com.compressly

import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.video.VideoPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-aware Smart planning: what the analysis actually changes in the
 * numbers, and what it must NOT change (un-analysed files keep the old path).
 */
class SmartPlanningTest {

    private fun info4k(
        bitrate: Int = 30_000_000,
        fps: Int = 30,
        complexity: Float = -1f
    ) = MediaInfo(
        width = 3840, height = 2160, rotation = 0,
        durationMs = 30_000L, frameRate = fps,
        videoBitrate = bitrate, audioBitrate = 0,
        hasVideo = true, hasAudio = true,
        mimeType = "video/mp4",
        complexity = complexity
    )

    @Test
    fun unAnalysedFilesKeepPreviousSmartBudget() {
        val plain = VideoPlanner.smartBitrate(1920, 1080, 30, VideoCodec.H264)
        val neutral = VideoPlanner.smartBitrate(1920, 1080, 30, VideoCodec.H264, complexity = -1f)
        assertEquals(plain, neutral)
    }

    @Test
    fun higherComplexityMeansHigherSmartBudget() {
        val calm = VideoPlanner.smartBitrate(1920, 1080, 30, VideoCodec.H264, complexity = 0.15f)
        val busy = VideoPlanner.smartBitrate(1920, 1080, 30, VideoCodec.H264, complexity = 0.85f)
        assertTrue("busy ($busy) should cost more than calm ($calm)", busy > calm)
        // Exactly the documented factor mapping: 0.85 → 0.78 + 0.72*0.85 = 1.392.
        val base = VideoPlanner.smartBitrate(1920, 1080, 30, VideoCodec.H264)
        val expected = (base * 1.392).toInt()
        assertTrue(
            "busy ($busy) should equal base×1.392 ($expected)",
            kotlin.math.abs(busy - expected) <= base / 50
        )
    }

    @Test
    fun smartEdge_isFixed1920ForUnanalysed4k() {
        val info = info4k(bitrate = 30_000_000)
        assertEquals(1920, VideoPlanner.smartResolutionEdge(info, VideoSettings()))
    }

    @Test
    fun smartEdge_stepsDownWhenSourceCannotAffordThePixels() {
        // 4K at 4 Mbps is a badly thin stream: 1080p would need ~2.8 Mbps of a
        // 2.2 Mbps budget -> 720p is the honest choice.
        val thin = info4k(bitrate = 4_000_000)
        val edge = VideoPlanner.smartResolutionEdge(thin, VideoSettings())
        assertEquals(1280, edge)
    }

    @Test
    fun smartEdge_hevcLadderHasLowerFloorAndKeeps1080p() {
        // Same thin source, but HEVC floor is 0.026 -> 1920 needs only 1.6 Mbps.
        val thin = info4k(bitrate = 4_000_000)
        val edge = VideoPlanner.smartResolutionEdge(
            thin, VideoSettings(codec = VideoCodec.H265)
        )
        assertEquals(1920, edge)
    }

    @Test
    fun smartEdge_neverExceedsSourceEdge() {
        val info = MediaInfo(
            width = 1920, height = 1080, durationMs = 10_000L,
            frameRate = 30, videoBitrate = 2_000_000,
            hasVideo = true, complexity = 0.9f
        )
        assertEquals(1920, VideoPlanner.smartResolutionEdge(info, VideoSettings()))
    }

    @Test
    fun smartEdge_portraitKeepsAspect() {
        val portrait = MediaInfo(
            width = 2160, height = 3840, durationMs = 10_000L,
            frameRate = 30, videoBitrate = 30_000_000,
            hasVideo = true, complexity = -1f
        )
        assertEquals(1920, VideoPlanner.smartResolutionEdge(portrait, VideoSettings()))
    }

    @Test
    fun analysedTargetNeverExceedsSourceShare() {
        // The no-inflation rule must survive the complexity multiplier.
        val info = info4k(bitrate = 6_000_000, complexity = 0.95f)
        val target = VideoPlanner.targetVideoBitrate(info, VideoSettings(), com.compressly.core.engine.model.CompressionPreset.SMART)
        assertTrue("target $target must stay below 97% of source 6 Mbps", target < 6_000_000)
    }
}
