package com.compressly

import com.compressly.core.engine.photo.SmartPhotoAdvisor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Smart photo ladder is content-aware: text and smooth gradients stay
 * high (artifacts show first there), noisy/saturated content may start lower
 * (noise hides compression), and the classic ladder is the fallback.
 */
class SmartPhotoAdvisorTest {

    private fun frame(vararg packed: Int): IntArray {
        val out = IntArray(packed.size * 36)
        for (i in packed.indices) {
            val c = packed[i]
            for (j in 0 until 36) out[i * 36 + j] = (0xFF shl 24) or c
        }
        return out
    }

    /** Even 0..255 ramp in rgb: every luma level, no chroma. */
    private fun ramp(): IntArray {
        val out = IntArray(768)
        for (i in out.indices) {
            val v = (i * 255) / (out.size - 1)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    @Test
    fun metrics_emptyIsNeutral() {
        val m = SmartPhotoAdvisor.metricsOf(IntArray(0))
        assertEquals(0f, m.lumaStdDev, 0.001f)
        assertEquals(0, m.lumaBins)
        assertEquals(0f, m.saturation, 0.001f)
    }

    @Test
    fun textLike_getsTheHighestLadder() {
        // Two grey levels with strong contrast: a screenshot / document.
        val m = SmartPhotoAdvisor.metricsOf(frame(0x000000, 0xFFFFFF))
        val ladder = SmartPhotoAdvisor.ladderFor(m)
        assertEquals(92, ladder[0])
        // Never below the floor the promise keeps.
        assertEquals(85, ladder.last())
    }

    @Test
    fun smoothLowChroma_neverDropsBelow78() {
        val m = SmartPhotoAdvisor.metricsOf(frame(0x808080, 0x909090))
        val ladder = SmartPhotoAdvisor.ladderFor(m)
        assertEquals(85, ladder[0])
        assertEquals(78, ladder.last())
    }

    @Test
    fun busyOrSaturated_mayStartLower() {
        // A saturated hue ramp over grey levels: many luma bins (NOT text-like)
        // and very high chroma — the "noise hides artifacts" case.
        val hue = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF)
        val busy = IntArray(256)
        for (i in 0 until 256) {
            busy[i] = if (i % 2 == 0) (0xFF shl 24) or (i shl 16) or (i shl 8) or i
            else (0xFF shl 24) or hue[(i / 2) % 6]
        }
        val m = SmartPhotoAdvisor.metricsOf(busy)
        assertTrue("bin count ${m.lumaBins} must exceed the text threshold", m.lumaBins > 6)
        val ladder = SmartPhotoAdvisor.ladderFor(m)
        assertArrayEquals(intArrayOf(80, 76, 72), ladder)
    }

    @Test
    fun defaultLadder_untouched() {
        // A normal photo-like mixture: many luma levels, moderate chroma.
        val m = SmartPhotoAdvisor.metricsOf(ramp())
        assertArrayEquals(intArrayOf(85, 78, 72), SmartPhotoAdvisor.ladderFor(m))
    }

    @Test
    fun allLaddersRespectThePerceptualFloor() {
        // Whatever the content, Smart never asks for less than 72.
        val cases = listOf(
            SmartPhotoAdvisor.ladderFor(SmartPhotoAdvisor.metricsOf(frame(0x000000, 0xFFFFFF))),
            SmartPhotoAdvisor.ladderFor(SmartPhotoAdvisor.metricsOf(frame(0x808080))),
            SmartPhotoAdvisor.ladderFor(SmartPhotoAdvisor.metricsOf(frame(0xFF0000, 0x00FF00))),
            SmartPhotoAdvisor.ladderFor(SmartPhotoAdvisor.metricsOf(ramp()))
        )
        for (ladder in cases) assertTrue(ladder.last() >= 72)
    }
}
