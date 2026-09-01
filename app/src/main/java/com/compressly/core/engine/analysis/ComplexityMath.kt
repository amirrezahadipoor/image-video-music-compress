package com.compressly.core.engine.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure numeric core of the content-analysis feature. No android.* imports, so
 * the JVM unit tests cover it directly.
 *
 * What it does: turns a handful of tiny decoded frames into three numbers that
 * describe how expensive a video is to compress well —
 *
 *  - [detail]: spatial detail. Edges, textures and noise are what a codec has
 *    to preserve; a flat talking-head shot costs a fraction of a forest shot
 *    at the same resolution.
 *  - [motion]: temporal change between frames. Inter-frame prediction is what
 *    makes video codecs cheap; fast action on a phone camera produces lots of
 *    fresh pixels per second and a huge bitrate demand.
 *  - [color]: chroma spread. Saturated, colorful footage needs more bits than
 *    greyscale at the same detail and motion.
 *
 * The three are folded into one 0..1 [ComplexityScore] (drives the Smart
 * bitrate) and shown in the UI both as bars and as a plain-language label.
 */
object ComplexityMath {

    /** Luminance (Rec. 601, integer-only) of one ARGB pixel. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Spatial detail of one frame: standard deviation of luma, normalised to
     * 0..1. A photo of a blank wall scores ~0; a busy city scene ~0.35+.
     *
     * Unbiased (n-1) sample variance; empty/single-pixel frames score 0.
     */
    fun detailOf(luma: IntArray): Float {
        val n = luma.size
        if (n < 2) return 0f
        var sum = 0.0
        var sumSq = 0.0
        for (v in luma) {
            sum += v
            sumSq += v.toDouble() * v
        }
        val mean = sum / n
        val variance = (sumSq - sum * mean) / (n - 1)
        if (variance <= 0.0) return 0f
        // Saturation at ~40 luma-std-dev is already very detailed footage.
        return (sqrt(variance) / 40.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Temporal motion between two consecutive frames: mean absolute luma
     * difference, normalised to 0..1 (a full-scene cut scores ~0.10+).
     */
    fun motionOf(prev: IntArray, next: IntArray): Float {
        val n = min(prev.size, next.size)
        if (n == 0) return 0f
        var sum = 0L
        for (i in 0 until n) sum += abs(prev[i] - next[i])
        return (sum.toDouble() / n / 30.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Colour richness of one frame: mean chroma spread (max(R,G,B)-min(R,G,B))
     * normalised to 0..1. Greyscale ≈ 0; saturated footage ≈ 0.35+.
     */
    fun colorOf(argb: IntArray): Float {
        val n = argb.size
        if (n == 0) return 0f
        var sum = 0L
        for (v in argb) {
            val r = (v shr 16) and 0xFF
            val g = (v shr 8) and 0xFF
            val b = v and 0xFF
            sum += max(r, max(g, b)) - min(r, min(g, b))
        }
        return (sum.toDouble() / n / 110.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Folds [detail], [motion] and [color] into one 0..1 complexity score.
     *
     * Motion dominates on purpose: at a fixed quality, the bitrate a clip needs
     * scales almost entirely with how many fresh pixels per second it has.
     * Detail and colour are secondary correction terms. A static interview
     * (detail 0.35, motion 0.02, colour 0.10) scores ~0.09; a 60 fps sports
     * clip (detail 0.45, motion 0.45, colour 0.35) scores ~0.50.
     */
    fun score(detail: Float, motion: Float, color: Float): Float =
        score(detail, motion, color, sceneCuts = 0)

    /**
     * [sceneCuts] — how many of the sampled consecutive pairs look like a
     * scene change. A cut resets inter-frame prediction: the encoder must send
     * a fresh key frame (or suffer visible blocking at long GOPs), so a
     * cut-heavy clip genuinely costs more bits than a one-shot take with the
     * same motion. The boost is deliberately small (+0.05 per cut, capped at
     * +0.20): motion already carries most of the signal, this only stops
     * multi-scene clips from being priced as a single still shot.
     */
    fun score(detail: Float, motion: Float, color: Float, sceneCuts: Int): Float =
        ((0.12f * detail.coerceIn(0f, 1f) +
            0.60f * motion.coerceIn(0f, 1f) +
            0.28f * color.coerceIn(0f, 1f)) +
            0.05f * sceneCuts.coerceIn(0, 4)).coerceIn(0f, 1f)

    /**
     * How much extra bitrate an encode of this content deserves, relative to
     * an "average" clip. 0 → 0.78×, 0.5 → 1.14×, 1 → 1.50×.
     *
     * Never below 0.75 and never above 1.5: the tier's own bpp budget is what
     * keeps the promise, the complexity term only prices the *content* side of
     * the quality equation so a fast pan does not become blocky while a still
     * shot is not paying for bandwidth it does not use.
     */
    fun bitrateFactor(score: Float): Float =
        (0.78f + 0.72f * score.coerceIn(0f, 1f)).coerceIn(0.78f, 1.50f)

    /** Plain-language label for the UI. */
    enum class MotionLabel { STATIC, GENTLE, DYNAMIC }

    fun motionLabel(score: Float): MotionLabel = when {
        score < 0.22f -> MotionLabel.STATIC
        score < 0.48f -> MotionLabel.GENTLE
        else -> MotionLabel.DYNAMIC
    }

    /** Median of a list of frame values (robust against one bad sample). */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * Scene-cut test between two frames: 1 minus the normalised intersection
     * of their 16-bin luma histograms. Two frames of the SAME scene (even
     * with fast motion or a pan) keep a similar luminance histogram; two
     * frames of DIFFERENT scenes share almost none of it, so the distance
     * jumps to ~0.5+. Retina-safe: 16 bins over 0..255, integer arithmetic.
     */
    fun histogramDistance(a: IntArray, b: IntArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        val ha = IntArray(16)
        val hb = IntArray(16)
        for (i in 0 until n) {
            ha[(a[i].coerceIn(0, 255) * 16) / 256]++
            hb[(b[i].coerceIn(0, 255) * 16) / 256]++
        }
        var intersect = 0L
        for (k in 0 until 16) intersect += min(ha[k], hb[k])
        return (1.0 - intersect.toDouble() / n).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Motion statistic that prices a clip by what it actually contains.
     *
     * A pure median hides the short hard stretches — a 3-minute talking head
     * with one 4-second fast pan needs the bits for that pan, not for the
     * average frame. A pure max is the opposite failure: one corrupt probe
     * would price the whole clip as action. With only a handful of probe
     * pairs, the honest middle is an even blend of the two: the median keeps
     * it robust, the max makes sure a single fast section is never silently
     * compressed at talking-head rates (bounded by the source-share brake
     * upstream, so even a wild value cannot inflate a file).
     */
    fun motionScore(pairs: List<Float>): Float =
        if (pairs.isEmpty()) 0f
        else 0.5f * median(pairs) + 0.5f * (pairs.maxOrNull() ?: 0f)
}
