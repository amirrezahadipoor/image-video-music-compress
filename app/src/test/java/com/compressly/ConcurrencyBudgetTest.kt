package com.compressly

import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.video.VideoConcurrency
import com.compressly.core.service.EngineGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONCURRENCY-5: the app processes up to five files at once, and every media
 * type asks for exactly as much of that budget as it is safe to take.
 */
class ConcurrencyBudgetTest {

    @Test
    fun `ordinary photo and mp3 work take one slot each`() {
        assertEquals(1, EngineGate.weightForSlots(5))
        assertEquals(5, EngineGate.MAX_CONCURRENT_ITEMS)
    }

    @Test
    fun `two at a time costs two slots`() {
        // 2 + 2 = 4 <= 5 and 2 + 2 + 2 = 6 > 5, so exactly two run.
        assertEquals(2, EngineGate.weightForSlots(2))
        assertEquals(2, EngineGate.MAX_CONCURRENT_ITEMS / EngineGate.weightForSlots(2))
    }

    @Test
    fun `three or four at a time round down to a safe pair`() {
        // Five slots cannot express "three at a time"; the closest safe
        // arrangement is a pair, and it must never run more than asked.
        assertEquals(2, EngineGate.weightForSlots(3))
        assertEquals(2, EngineGate.weightForSlots(4))
        assertEquals(2, EngineGate.MAX_CONCURRENT_ITEMS / EngineGate.weightForSlots(3))
    }

    @Test
    fun `one at a time costs the whole budget`() {
        assertEquals(5, EngineGate.weightForSlots(1))
        assertEquals(1, EngineGate.MAX_CONCURRENT_ITEMS / EngineGate.weightForSlots(1))
    }

    @Test
    fun `weights are clamped into the budget`() {
        assertEquals(1, EngineGate.weightForSlots(99))
        assertEquals(5, EngineGate.weightForSlots(0))
        assertEquals(5, EngineGate.weightForSlots(-3))
    }

    // ── video: the device decides, never a guess ───────────────────────────

    @Test
    fun `a device advertising many codec instances gets the full five`() {
        assertEquals(5, VideoConcurrency.slotsForReportedInstances(7))
        assertEquals(5, VideoConcurrency.slotsForReportedInstances(32))
    }

    @Test
    fun `a device advertising four instances runs two at a time`() {
        assertEquals(2, VideoConcurrency.slotsForReportedInstances(4))
        assertEquals(2, VideoConcurrency.weightFor(2))
    }

    @Test
    fun `a device advertising two instances stays sequential`() {
        assertEquals(1, VideoConcurrency.slotsForReportedInstances(2))
        assertEquals(5, VideoConcurrency.weightFor(1))
    }

    @Test
    fun `an unknown or refused capability query is sequential`() {
        assertEquals(1, VideoConcurrency.slotsForReportedInstances(0))
        assertEquals(1, VideoConcurrency.slotsForReportedInstances(-1))
        assertTrue(VideoConcurrency.weightFor(1) >= 1)
    }

    @Test
    fun `each codec maps to the mime the platform knows`() {
        assertEquals("video/avc", VideoConcurrency.mimeFor(VideoCodec.H264))
        assertEquals("video/hevc", VideoConcurrency.mimeFor(VideoCodec.H265))
        assertEquals("video/av01", VideoConcurrency.mimeFor(VideoCodec.AV1))
    }
}
