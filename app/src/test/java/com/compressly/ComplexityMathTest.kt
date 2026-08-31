package com.compressly

import com.compressly.core.engine.analysis.ComplexityMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplexityMathTest {

    /** One solid colour per argument, packed 0xRRGGBB, 9 pixels each. */
    private fun frame(vararg packed: Int): IntArray {
        val out = IntArray(packed.size * 9)
        for (i in packed.indices) {
            val c = packed[i]
            for (j in 0 until 9) out[i * 9 + j] = (0xFF shl 24) or c
        }
        return out
    }

    @Test
    fun luma_isRec601() {
        assertEquals(0, ComplexityMath.luma(0xFF000000.toInt()))
        assertEquals(255, ComplexityMath.luma(0xFFFFFFFF.toInt()))
        // A mid-grey is the same value in R, G and B, so luma == itself.
        assertEquals(128, ComplexityMath.luma(0xFF808080.toInt()))
    }

    @Test
    fun detail_flatFrameIsZero() {
        assertEquals(0f, ComplexityMath.detailOf(frame(0x808080, 0x808080, 0x808080)), 0.001f)
        assertEquals(0f, ComplexityMath.detailOf(IntArray(0)), 0.001f)
    }

    @Test
    fun detail_checkerboardIsHigherThanFlat() {
        val flat = ComplexityMath.detailOf(frame(128, 128, 128))
        val checker = ComplexityMath.detailOf(frame(0x000000, 0xFFFFFF, 0x000000, 0xFFFFFF, 0x000000, 0xFFFFFF))
        assertTrue("checkerboard ($checker) should beat flat ($flat)", checker > flat)
        assertTrue(checker in 0f..1f)
    }

    @Test
    fun motion_identicalFramesIsZero() {
        val a = frame(0x5A5A5A, 0x5A5A5A, 0x5A5A5A)
        assertEquals(0f, ComplexityMath.motionOf(a, a.copyOf()), 0.001f)
    }

    @Test
    fun motion_fullCutSaturatesAtOne() {
        val a = frame(0x000000, 0x000000, 0x000000)
        val b = frame(0xFFFFFF, 0xFFFFFF, 0xFFFFFF)
        val motion = ComplexityMath.motionOf(a, b)
        assertEquals(1f, motion, 0.001f)
    }

    @Test
    fun colour_greyscaleIsZeroRedIsMax() {
        assertEquals(0f, ComplexityMath.colorOf(frame(0x646464, 0x646464, 0x646464)), 0.001f)
        assertEquals(1f, ComplexityMath.colorOf(frame(0xFF0000, 0x00FF00, 0x0000FF)), 0.001f)
    }

    @Test
    fun score_isClampedAndMonotonic() {
        assertEquals(0f, ComplexityMath.score(-1f, -1f, -1f), 0.001f)
        assertEquals(1f, ComplexityMath.score(2f, 2f, 2f), 0.001f)
        assertTrue(ComplexityMath.score(0.5f, 0.5f, 0.5f) in 0.4f..0.6f)
        // Motion has the biggest weight by design.
        val low = ComplexityMath.score(0.5f, 0.1f, 0.5f)
        val high = ComplexityMath.score(0.5f, 0.9f, 0.5f)
        assertTrue(high > low)
    }

    @Test
    fun bitrateFactor_isBoundedAndMonotonic() {
        assertEquals(0.78f, ComplexityMath.bitrateFactor(0f), 0.001f)
        assertEquals(1.50f, ComplexityMath.bitrateFactor(1f), 0.001f)
        assertEquals(0.78f, ComplexityMath.bitrateFactor(-5f), 0.001f) // clamped to score 0
        assertTrue(ComplexityMath.bitrateFactor(0.5f) > ComplexityMath.bitrateFactor(0.2f))
        // Never below the safe floor, never above the generous ceiling.
        for (s in listOf(0f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f)) {
            val f = ComplexityMath.bitrateFactor(s)
            assertTrue(f in 0.78f..1.50f)
        }
    }

    @Test
    fun motionLabel_thresholds() {
        assertEquals(ComplexityMath.MotionLabel.STATIC, ComplexityMath.motionLabel(0.10f))
        assertEquals(ComplexityMath.MotionLabel.GENTLE, ComplexityMath.motionLabel(0.35f))
        assertEquals(ComplexityMath.MotionLabel.DYNAMIC, ComplexityMath.motionLabel(0.80f))
    }

    @Test
    fun median_oddAndEven() {
        assertEquals(3f, ComplexityMath.median(listOf(3f)), 0.001f)
        assertEquals(3f, ComplexityMath.median(listOf(1f, 3f, 5f)), 0.001f)
        assertEquals(4f, ComplexityMath.median(listOf(1f, 3f, 5f, 7f)), 0.001f)
        assertEquals(0f, ComplexityMath.median(emptyList()), 0.001f)
    }
}
