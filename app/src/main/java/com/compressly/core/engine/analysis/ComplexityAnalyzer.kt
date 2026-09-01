package com.compressly.core.engine.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Frames a video at regular intervals and measures its motion / detail /
 * colour content. This is what makes Smart compression *content-aware*:
 * a still interview and a mountain-bike ride at the same resolution get
 * different bitrates, because at the same perceived quality they actually
 * need different bitrates.
 *
 * Cost: 7 tiny frames (max 160 px long edge) decoded with the hardware
 * decoder, a few ms of CPU each. On a low-end phone this is well under a
 * second for a 10-minute clip, and it runs off the main thread. Any failure
 * (corrupt container, unsupported codec, OOM) degrades to "unknown" — the
 * planner then behaves exactly as before, so analysis can only ever improve
 * the result, never break it.
 */
object ComplexityAnalyzer {

    /** Result: 0..1 features, or null when the file could not be sampled. */
    data class Result(
        val complexity: Float,
        val motion: Float,
        val detail: Float,
        val color: Float,
        /** Consecutive sampled pairs that look like a scene change. */
        val sceneCuts: Int = 0
    )

    /**
     * CACHE-FIX: the settings screen re-runs the 7-frame probe every time it
     * is opened for the same file (and the job runner does too). The probe is
     * cheap but not free — a few hundred ms of decoder work on a low-end
     * phone, plus a visible delay before the estimate updates. The result is a
     * pure function of the file, so keep a small TTL cache keyed by URI.
     * 64 entries / 15 min is far more than a user needs; anything stale is
     * simply recomputed. Never grows unbounded (cleared when full), and the
     * check happens before any MediaMetadataRetriever work.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Result>>()
    private const val CACHE_TTL_MS = 15 * 60_000L
    private const val CACHE_MAX = 64

    private const val TARGET_EDGE = 160
    private const val MIN_DURATION_MS = 300L

    /** Number of samples: 7 keeps the probe under ~0.5 s on mid-range phones. */
    private const val SAMPLES = 7

    /**
     * Clips shorter than this are sampled with OPTION_CLOSEST instead of
     * OPTION_CLOSEST_SYNC. MEDIA-FIX: on many devices OPTION_CLOSEST_SYNC snaps
     * every probe to the same keyframe when the sample spacing is below the
     * GOP length — identical frames then produce a motion of ~0 and a still
     * clip, no matter how much it actually moves. Decoding straight to the
     * requested time is only a few extra frames per probe for clips this short.
     */
    private const val SHORT_CLIP_SYNC_MS = 15_000L

    /** Histogram distance above which two sampled frames count as a scene change. */
    private const val CUT_DISTANCE = 0.30f

    fun analyze(context: Context, uri: Uri, durationMs: Long, cancelCheck: (() -> Boolean)? = null): Result? {
        if (durationMs < MIN_DURATION_MS) return null
        // Cache hit: skip the decoder entirely (see CACHE-FIX above).
        if (cancelCheck == null) {
            cache[uri.toString()]?.let { (ts, result) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return result
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val detailSamples = ArrayList<Float>(SAMPLES)
            val colorSamples = ArrayList<Float>(SAMPLES)
            val motionSamples = ArrayList<Float>(SAMPLES)
            var prevLuma: IntArray? = null
            var sceneCuts = 0

            for (i in 0 until SAMPLES) {
                cancelCheck?.let { if (it()) return null }
                val timeUs = (durationMs * (2 * i + 1) / (2 * SAMPLES)) * 1000L
                val frame = grabScaled(retriever, timeUs, durationMs) ?: continue
                try {
                    val luma = IntArray(frame.width * frame.height)
                    val argb = IntArray(frame.width * frame.height)
                    frame.getPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
                    for (p in argb.indices) luma[p] = ComplexityMath.luma(argb[p])

                    detailSamples += ComplexityMath.detailOf(luma)
                    colorSamples += ComplexityMath.colorOf(argb)
                    // MOTION-FIX: a probe that returned the *same* frame as the
                    // previous one carries no motion information — counting it
                    // as a 0-difference pair drags a real pan down to "static".
                    // Skip the pair; a genuinely frozen shot then has no pairs
                    // at all and scores 0, which is the honest answer anyway.
                    val prev = prevLuma
                    if (prev != null && !prev.contentEquals(luma)) {
                        motionSamples += ComplexityMath.motionOf(prev, luma)
                        // Scene-cut count: histogram distance between the two
                        // sampled frames. Cheap (16 bins) and robust against a
                        // pan, which motion alone cannot distinguish from a cut.
                        if (ComplexityMath.histogramDistance(prev, luma) >= CUT_DISTANCE) {
                            sceneCuts++
                        }
                    }
                    prevLuma = luma
                } finally {
                    frame.recycle()
                }
            }
            if (detailSamples.size < 3) return null

            val detail = ComplexityMath.median(detailSamples)
            val color = ComplexityMath.median(colorSamples)
            // Median + max blend (motionScore): median keeps the statistic
            // robust, max makes sure a single fast section is never priced as
            // a still shot.
            val motion = ComplexityMath.motionScore(motionSamples)
            Result(
                complexity = ComplexityMath.score(detail, motion, color, sceneCuts),
                motion = motion,
                detail = detail,
                color = color,
                sceneCuts = sceneCuts
            ).also { result ->
                if (cancelCheck == null) {
                    if (cache.size >= CACHE_MAX) cache.clear()
                    cache[uri.toString()] = System.currentTimeMillis() to result
                }
            }
        } catch (t: Throwable) {
            // Any probe failure is non-fatal by design.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** One frame at [timeUs], already scaled down to [TARGET_EDGE]. */
    private fun grabScaled(retriever: MediaMetadataRetriever, timeUs: Long, durationMs: Long): Bitmap? = runCatching {
        val option = if (durationMs < SHORT_CLIP_SYNC_MS)
            MediaMetadataRetriever.OPTION_CLOSEST
        else
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            // getScaledFrameAtTime decodes straight to the target size; the
            // hardware decoder does the scaling, so a 4K frame never materialises.
            retriever.getScaledFrameAtTime(timeUs, option, TARGET_EDGE, TARGET_EDGE)
        } else {
            // API 26: full frame then downscale. Only 7 frames, released
            // immediately; guarded by the outer runCatching against OOM.
            val full = retriever.getFrameAtTime(timeUs, option) ?: return@runCatching null
            val scale = minOf(
                TARGET_EDGE.toFloat() / full.width,
                TARGET_EDGE.toFloat() / full.height,
                1f
            )
            if (scale >= 1f) {
                full
            } else {
                val w = (full.width * scale).toInt().coerceAtLeast(1)
                val h = (full.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(full, w, h, true)
                if (scaled != full) full.recycle()
                scaled
            }
        }
    }.getOrNull()
}
