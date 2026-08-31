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
        val color: Float
    )

    private const val TARGET_EDGE = 160
    private const val MIN_DURATION_MS = 300L

    /** Number of samples: 7 keeps the probe under ~0.5 s on mid-range phones. */
    private const val SAMPLES = 7

    fun analyze(context: Context, uri: Uri, durationMs: Long, cancelCheck: (() -> Boolean)? = null): Result? {
        if (durationMs < MIN_DURATION_MS) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val detailSamples = ArrayList<Float>(SAMPLES)
            val colorSamples = ArrayList<Float>(SAMPLES)
            val motionSamples = ArrayList<Float>(SAMPLES)
            var prevLuma: IntArray? = null

            for (i in 0 until SAMPLES) {
                cancelCheck?.let { if (it()) return null }
                val timeUs = (durationMs * (2 * i + 1) / (2 * SAMPLES)) * 1000L
                val frame = grabScaled(retriever, timeUs) ?: continue
                try {
                    val luma = IntArray(frame.width * frame.height)
                    val argb = IntArray(frame.width * frame.height)
                    frame.getPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
                    for (p in argb.indices) luma[p] = ComplexityMath.luma(argb[p])

                    detailSamples += ComplexityMath.detailOf(luma)
                    colorSamples += ComplexityMath.colorOf(argb)
                    prevLuma?.let { motionSamples += ComplexityMath.motionOf(it, luma) }
                    prevLuma = luma
                } finally {
                    frame.recycle()
                }
            }
            if (detailSamples.size < 3) return null

            val detail = ComplexityMath.median(detailSamples)
            val color = ComplexityMath.median(colorSamples)
            val motion = if (motionSamples.isEmpty()) 0f else ComplexityMath.median(motionSamples)
            Result(
                complexity = ComplexityMath.score(detail, motion, color),
                motion = motion,
                detail = detail,
                color = color
            )
        } catch (t: Throwable) {
            // Any probe failure is non-fatal by design.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** One frame at [timeUs], already scaled down to [TARGET_EDGE]. */
    private fun grabScaled(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            // getScaledFrameAtTime decodes straight to the target size; the
            // hardware decoder does the scaling, so a 4K frame never materialises.
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                TARGET_EDGE, TARGET_EDGE
            )
        } else {
            // API 26: full frame then downscale. Only 7 frames, released
            // immediately; guarded by the outer runCatching against OOM.
            val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
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
