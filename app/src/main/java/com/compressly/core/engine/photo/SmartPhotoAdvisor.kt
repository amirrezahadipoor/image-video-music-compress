package com.compressly.core.engine.photo

import com.compressly.core.engine.analysis.ComplexityMath
import kotlin.math.sqrt

/**
 * Chooses the quality ladder a Smart-mode photo encode starts on, from what
 * the image actually contains. Pure Kotlin — no android.* — so the JVM tests
 * cover it, and it only ever picks the *starting* rung: the existing size
 * loop still descends the ladder until the 50 % target is met, so this can
 * never make a file larger than the old behaviour.
 *
 * Why: a fixed 85/78/72 ladder treats every image the same, but artifacts
 * are content-dependent —
 *  - text and screenshots blur first (a 72 % screen recording is unreadable),
 *  - smooth skies/skin band visibly at 78 but barely at 85,
 *  - a noisy city photo hides 72 % compression almost completely.
 * Pricing per content is the same idea the video engine already uses.
 */
object SmartPhotoAdvisor {

    data class Metrics(
        /** Luminance standard deviation, normalised 0..1. */
        val lumaStdDev: Float,
        /** How many of the 32 luminance levels the image actually uses (1..32). */
        val lumaBins: Int,
        /** Mean chroma spread (0..1): how colourful the image is. */
        val saturation: Float
    )

    /**
     * Metrics from a (possibly strided) sample of the decoded ARGB pixels.
     * Two cheap passes over the sample; a few hundred pixels is plenty.
     */
    fun metricsOf(sampled: IntArray): Metrics {
        val n = sampled.size
        if (n == 0) return Metrics(0f, 0, 0f)
        val luma = IntArray(n)
        for (i in 0 until n) luma[i] = ComplexityMath.luma(sampled[i])
        var sum = 0.0
        for (v in luma) sum += v
        val mean = sum / n
        var sq = 0.0
        for (v in luma) sq += (v - mean) * (v - mean)
        val stdDev = (sqrt(sq / n) / 255.0).toFloat()
        val bins = BooleanArray(32)
        for (v in luma) bins[(v.coerceIn(0, 255) * 31) / 255] = true
        val binCount = bins.count { it }
        return Metrics(stdDev, binCount.coerceAtLeast(1), ComplexityMath.colorOf(sampled))
    }

    /**
     * The starting ladder for this image (see class doc). Ladders are never
     * longer than the old one and never end below 72, so the Smart quality
     * promise (~70 % perceptual floor) holds on every path.
     */
    fun ladderFor(m: Metrics): IntArray = when {
        // Text/screenshot: few grey levels, strong contrast. Blurred text is
        // the single most visible artifact — stay at the top of the range.
        m.lumaBins in 1..6 && m.lumaStdDev >= 0.12f -> intArrayOf(92, 88, 85)
        // Smooth, low-chroma content (skies, plain backgrounds, portraits):
        // banding shows early, so never descend below 78.
        m.lumaStdDev <= 0.16f && m.saturation <= 0.18f -> intArrayOf(85, 82, 78)
        // Very busy or very saturated: noise hides compression artifacts.
        m.lumaStdDev >= 0.42f || m.saturation >= 0.60f -> intArrayOf(80, 76, 72)
        // Everything else: the classic ladder.
        else -> intArrayOf(85, 78, 72)
    }
}
