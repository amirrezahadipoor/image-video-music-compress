package com.compressly

import com.compressly.core.engine.audio.AudioPlanner
import com.compressly.core.engine.estimate.SizeEstimator
import com.compressly.core.engine.model.AudioBitrateMode
import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PresetDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the audio rate rule. The bug this pins: the target rate came straight
 * from the preset and was never compared with the source, so a 64 kbps voice
 * memo compressed on the Smart preset (192 kbps) came back three times larger
 * than it went in.
 */
class AudioPlannerTest {

    private val seconds = 60
    private fun MediaInfo.bytes() = (audioBitrate.toLong() * seconds) / 8

    private fun info(kbps: Int, durationMs: Long = 60_000) = MediaInfo(
        durationMs = durationMs, audioBitrate = kbps * 1000, hasAudio = true
    )

    private val voiceMemo = info(64)          // typical phone voice recording
    private val podcast = info(96)
    private val cdRip = info(320)

    @Test
    fun targetNeverExceedsTheSourceRate() {
        for (source in listOf(voiceMemo, podcast, cdRip)) {
            for (preset in CompressionPreset.entries) {
                val settings = PresetDefaults.audioSettingsFor(preset)
                val target = AudioPlanner.targetBitrateKbps(settings.bitrate, source.audioBitrate)
                assertTrue(
                    "$preset on a ${source.audioBitrate / 1000} kbps source asked for $target kbps",
                    target <= source.audioBitrate / 1000
                )
            }
        }
    }

    @Test
    fun smartOnAVoiceMemoNoLongerInflatesIt() {
        val settings = PresetDefaults.audioSettingsFor(CompressionPreset.SMART)
        val estimated = SizeEstimator.estimateAudio(voiceMemo, settings)
        assertTrue(
            "estimated $estimated B from a ${voiceMemo.bytes()} B source",
            estimated <= voiceMemo.bytes()
        )
        assertTrue(
            "and it is flagged as not worth re-encoding",
            AudioPlanner.shouldKeepOriginal(estimated, voiceMemo.bytes())
        )
    }

    @Test
    fun aRealReductionIsNotFlagged() {
        val settings = PresetDefaults.audioSettingsFor(CompressionPreset.MAXIMUM_COMPRESSION)
        val estimated = SizeEstimator.estimateAudio(cdRip, settings)
        assertTrue("320 -> 64 kbps must shrink", estimated < cdRip.bytes())
        assertFalse(AudioPlanner.shouldKeepOriginal(estimated, cdRip.bytes()))
    }

    @Test
    fun unknownSourceRateLeavesTheRequestAlone() {
        assertEquals(192, AudioPlanner.targetBitrateKbps(192, 0))
        assertEquals(320, AudioPlanner.targetBitrateKbps(999, 0))
        assertEquals(32, AudioPlanner.targetBitrateKbps(1, 0))
    }

    @Test
    fun sourceThinnerThanTheEncoderFloorIsReturnedUntouched() {
        assertEquals(16, AudioPlanner.targetBitrateKbps(192, 16_000))
    }

    @Test
    fun estimatesScaleWithTheRate() {
        val low = SizeEstimator.estimateAudio(info(64), AudioSettings(bitrate = 64))
        val high = SizeEstimator.estimateAudio(info(320), AudioSettings(bitrate = 320))
        assertTrue(high > low)
    }

    @Test
    fun estimateMatchesBitrateTimesDuration() {
        // 60 s at 128 kbps CBR = 128_000 * 60 / 8 = 960_000 bytes
        val e = SizeEstimator.estimateAudio(info(320), AudioSettings(bitrate = 128))
        assertTrue("got $e", e in 850_000..1_100_000)
    }

    @Test
    fun vbrEstimatesSmallerThanCbr() {
        val cbr = SizeEstimator.estimateAudio(info(320), AudioSettings(bitrate = 192, bitrateMode = AudioBitrateMode.CBR))
        val vbr = SizeEstimator.estimateAudio(info(320), AudioSettings(bitrate = 192, bitrateMode = AudioBitrateMode.VBR))
        assertTrue(vbr <= cbr)
    }

    @Test
    fun zeroDurationEstimatesZero() {
        assertEquals(0L, SizeEstimator.estimateAudio(info(192, 0), AudioSettings(bitrate = 192)))
    }

    @Test
    fun theDurationOnlyOverloadStillBehaves() {
        // Used by callers with no MediaInfo; must not silently change meaning.
        val e = SizeEstimator.estimateAudio(60_000, AudioSettings(bitrate = 128))
        assertTrue("got $e", e in 850_000..1_100_000)
    }
}
