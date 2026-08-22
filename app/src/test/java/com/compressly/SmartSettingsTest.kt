package com.compressly

import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.model.PresetDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSettingsTest {

    @Test
    fun smartPreset_usesAdaptiveQuality() {
        val s = PresetDefaults.photoSettingsFor(CompressionPreset.SMART)
        assertTrue("smart flag on", s.smart)
        assertTrue("starts at high quality", s.quality >= 80)
    }

    @Test
    fun manualPreset_disablesSmart() {
        for (p in listOf(
            CompressionPreset.MAXIMUM_QUALITY,
            CompressionPreset.BALANCED,
            CompressionPreset.HIGH_COMPRESSION,
            CompressionPreset.MAXIMUM_COMPRESSION
        )) {
            assertFalse("$p must not be smart", PresetDefaults.photoSettingsFor(p).smart)
        }
    }

    @Test
    fun smartVideoResolution_keepsOriginalButCapsAtRuntime() {
        // The SMART video default keeps ORIGINAL resolution; the 1920px cap is
        // applied at transcode time. Defaults must not force a lower tier.
        val v = PresetDefaults.videoSettingsFor(CompressionPreset.SMART)
        assertEquals(
            com.compressly.core.engine.model.VideoResolution.ORIGINAL,
            v.resolution
        )
    }

    @Test
    fun smartAudioBitrate_transparent() {
        val a = PresetDefaults.audioSettingsFor(CompressionPreset.SMART)
        assertTrue("smart audio bitrate >= 160kbps", a.bitrate >= 160)
    }

    @Test
    fun smartPresetIsFirstInAllList() {
        assertEquals(CompressionPreset.SMART, CompressionPreset.all.first())
    }
}
