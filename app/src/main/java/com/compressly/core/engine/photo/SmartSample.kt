package com.compressly.core.engine.photo

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Pure sampling math for the Smart-mode photo analyse. Extracted so the JVM
 * unit tests can pin the exact behaviour (the old inline stride under-sampled
 * large photos to a handful of pixels).
 *
 * No android.* imports: `SmartSampleTest` covers it directly on the JVM.
 */
object SmartSample {

    /**
     * Stride that samples ~[target] pixels spread across a whole WxH bitmap.
     *
     * The old formula `stride = (w*h)/target` produced only ~target²/(w·h)
     * samples — for a 4032x3024 photo that was 2-4 corner pixels. The correct
     * stride is the square root of (area/target), which yields
     * ~(w/step)·(h/step) ≈ target samples regardless of resolution.
     *
     * Always >= 1; a tiny bitmap samples at every pixel.
     */
    fun strideFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val area = width.toLong() * height
        val t = target.coerceAtLeast(1)
        return ceil(sqrt(area.toDouble() / t)).toInt().coerceAtLeast(1)
    }

    /**
     * Estimated number of samples a [strideFor] run collects over a WxH bitmap.
     * Used by tests to assert the sample target is actually reached.
     */
    fun estimatedSamples(width: Int, height: Int, target: Int): Int {
        val step = strideFor(width, height, target)
        if (width <= 0 || height <= 0) return 0
        val cols = (width + step - 1) / step
        val rows = (height + step - 1) / step
        return cols * rows
    }
}
