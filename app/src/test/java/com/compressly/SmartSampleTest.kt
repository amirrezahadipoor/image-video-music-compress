package com.compressly

import com.compressly.core.engine.photo.SmartSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Smart-mode photo sampling maths. The regression this guards against:
 * the old inline stride `step = (w*h)/target` collected only ~target²/(w·h)
 * samples, so a 12 MP photo was analysed from 2-4 corner pixels and the
 * detail/noise metrics that drive the quality ladder were effectively noise.
 */
class SmartSampleTest {

    companion object { private const val TARGET = 6_000 }

    @Test
    fun stride_isAtLeastOne_neverZero() {
        for ((w, h) in listOf(1 to 1, 10 to 10, 100 to 100, 4032 to 3024)) {
            assertTrue("stride for ${w}x$h must be >= 1", SmartSample.strideFor(w, h, TARGET) >= 1)
        }
    }

    @Test
    fun strideFor_zeroOrNegativeDim_returnsOne() {
        assertEquals(1, SmartSample.strideFor(0, 100, TARGET))
        assertEquals(1, SmartSample.strideFor(-5, 100, TARGET))
    }

    @Test
    fun strideFor_zeroTarget_isSafe() {
        // target.coerceAtLeast(1) guards against divide/zero in sqrt.
        assertTrue(SmartSample.strideFor(4000, 3000, 0) >= 1)
    }

    @Test
    fun twelveMegapixelPhoto_samplesThousands_notAPerCent() {
        // This is the actual regression: 4032x3024 under the OLD formula gave
        // ~2-4 samples. Under the new stride it must be in the thousands.
        val samples = SmartSample.estimatedSamples(4032, 3024, TARGET)
        assertTrue("expected thousands of samples, got $samples", samples >= 4_000)
        assertTrue("expected ~target samples, got $samples", samples <= TARGET * 2)
    }

    @Test
    fun sampleCount_scalesAcrossResolutions() {
        // A small and a large photo must both reach the sample target —
        // sampling must be resolution-independent in the number of samples.
        for ((w, h) in listOf(800 to 600, 1920 to 1080, 4032 to 3024, 8000 to 6000)) {
            val samples = SmartSample.estimatedSamples(w, h, TARGET)
            assertTrue("expected >=4000 samples for ${w}x$h, got $samples", samples >= 4_000)
            assertTrue("expected <=12000 samples for ${w}x$h, got $samples", samples <= TARGET * 2)
        }
    }

    @Test
    fun tinyBitmap_samplesEveryPixel() {
        // 10x10 with stride >=1 still collects far fewer than target — but it
        // must sample all 100 pixels (stride 1), not skip past them.
        val step = SmartSample.strideFor(10, 10, TARGET)
        assertEquals(1, step)
        assertEquals(100, SmartSample.estimatedSamples(10, 10, TARGET))
    }
}
