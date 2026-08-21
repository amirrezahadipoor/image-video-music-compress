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
}
