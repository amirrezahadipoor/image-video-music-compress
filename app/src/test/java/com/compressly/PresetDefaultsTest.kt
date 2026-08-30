package com.compressly

import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PresetDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetDefaultsTest {

    @Test
    fun allPresets_havePhotoDefaults() {
        for (p in CompressionPreset.entries) {
            assertNotNull("photo defaults for $p", PresetDefaults.photoDefaults[p])
        }
    }

    @Test
    fun allPresets_haveVideoDefaults() {
        for (p in CompressionPreset.entries) {
            assertNotNull("video defaults for $p", PresetDefaults.videoDefaults[p])
        }
    }

    @Test
    fun allPresets_haveAudioDefaults() {
        for (p in CompressionPreset.entries) {
            assertNotNull("audio defaults for $p", PresetDefaults.audioDefaults[p])
        }
    }

    @Test
    fun smartIsDefaultPreset() {
        assertEquals(CompressionPreset.SMART, CompressionPreset.DEFAULT)
    }

    @Test
    fun smartSettings_flagEnabled() {
        assertTrue(PresetDefaults.photoSettingsFor(CompressionPreset.SMART).smart)
        // Manual tiers must NOT enable smart.
        assertTrue(!PresetDefaults.photoSettingsFor(CompressionPreset.BALANCED).smart)
    }

    @Test
    fun reductionRange_validForAll() {
        for (media in MediaType.entries) {
            for (p in CompressionPreset.entries) {
                val (min, max) = PresetDefaults.reductionRange(p, media)
                assertTrue("min>=0", min >= 0)
                assertTrue("max>=min", max >= min)
                assertTrue("max<=100", max <= 100)
            }
        }
    }

    @Test
    fun manualOrder_strictlyIncreasing() {
        val orders = CompressionPreset.ordered.map { it.order }
        assertEquals(listOf(0, 1, 2, 3), orders)
    }

    @Test
    fun switchingLevelKeepsTheChoicesItDoesNotOwn() {
        val before = com.compressly.core.engine.model.VideoSettings(
            codec = com.compressly.core.engine.model.VideoCodec.H265,
            customWidth = 900,
            customHeight = 500,
            audioMode = com.compressly.core.engine.model.VideoAudioMode.STRIP,
            trimEnabled = true,
            trimStartMs = 1_500,
            trimEndMs = 9_000,
            bitrate = 4_000_000
        )
        val after = PresetDefaults.videoSettingsFor(CompressionPreset.HIGH_COMPRESSION, before)
        // Carried over.
        assertEquals(com.compressly.core.engine.model.VideoCodec.H265, after.codec)
        assertEquals(900, after.customWidth)
        assertEquals(500, after.customHeight)
        // NOT carried over: the level owns the audio mode, so it follows the
        // level. Carrying it over was the bug that left a full-rate soundtrack
        // next to a heavily compressed video.
        assertEquals(
            PresetDefaults.videoDefaults[CompressionPreset.HIGH_COMPRESSION]?.audioMode,
            after.audioMode
        )
        assertTrue(after.trimEnabled)
        assertEquals(1_500L, after.trimStartMs)
        assertEquals(9_000L, after.trimEndMs)
        // Owned by the level.
        assertEquals(
            com.compressly.core.engine.model.VideoResolution.R1080,
            after.resolution
        )
        assertEquals(
            PresetDefaults.videoDefaults[CompressionPreset.HIGH_COMPRESSION]?.frameRate,
            after.frameRate
        )
        // A stale manual bitrate must not survive: the level owns the rate.
        assertEquals(null, after.bitrate)
    }

    @Test
    fun videoSettingsWithoutAPreviousStateUsesDefaults() {
        val v = PresetDefaults.videoSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION, null)
        assertEquals(com.compressly.core.engine.model.VideoCodec.H264, v.codec)
        // The audio mode comes from the tier, not from a default: the most
        // aggressive tier compresses the soundtrack too.
        assertEquals(
            PresetDefaults.videoDefaults[CompressionPreset.MAXIMUM_COMPRESSION]?.audioMode,
            v.audioMode
        )
        assertEquals(com.compressly.core.engine.model.VideoAudioMode.COMPRESS, v.audioMode)
        assertEquals(false, v.trimEnabled)
    }
}
