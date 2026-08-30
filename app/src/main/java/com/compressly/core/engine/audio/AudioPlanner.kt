package com.compressly.core.engine.audio

/**
 * Decides the audio encode rate, and whether re-encoding is worth doing at all.
 *
 * Pure Kotlin so the JVM tests cover it, and shared by the live size estimate
 * and the real encoder so they cannot disagree - the same split that caused the
 * video engine to inflate files.
 *
 * Why this exists: the target rate came straight from the preset (Smart = 192
 * kbps, Maximum Quality = 320 kbps) and was never compared with the source. A
 * 64 kbps voice memo compressed on Smart came back three times *larger* than it
 * went in.
 */
object AudioPlanner {

    const val MIN_BITRATE_KBPS = 32
    const val MAX_BITRATE_KBPS = 320

    /** Above this share of the source rate, re-encoding only adds a generation of loss. */
    const val NO_GAIN_RATIO = 0.97

    /** Below this share of the input size an output counts as "not smaller". */
    const val NO_GAIN_SIZE_RATIO = 0.95

    /**
     * The rate to encode at, in kbps. Never above what the source already
     * carries: a transcode priced above its input can only make the file bigger.
     *
     * @param sourceBitrateBps the source audio track rate in bits/second, or 0
     * when the container does not report it (then the request stands).
     */
    fun targetBitrateKbps(requestedKbps: Int, sourceBitrateBps: Int): Int {
        val requested = requestedKbps.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
        if (sourceBitrateBps <= 0) return requested
        val sourceKbps = sourceBitrateBps / 1000
        // A source thinner than the encoder floor cannot be improved on; hand
        // back its own rate so the no-gain check below can keep the file.
        if (sourceKbps in 1 until MIN_BITRATE_KBPS) return sourceKbps
        val capped = minOf(requested, (sourceKbps * NO_GAIN_RATIO).toInt())
        return capped.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
    }

    /** Encoded size in bytes for a target rate. */
    fun estimatedBytes(bitrateKbps: Int, durationMs: Long, vbr: Boolean): Long {
        if (durationMs <= 0) return 0L
        val factor = if (vbr) 0.92 else 1.0
        return (durationMs * bitrateKbps.coerceAtLeast(1) / 8L * factor).toLong().coerceAtLeast(2_000)
    }

    /** True when re-encoding cannot shrink the file, so the original should be kept. */
    fun shouldKeepOriginal(estimatedOutputBytes: Long, inputSizeBytes: Long): Boolean =
        inputSizeBytes > 0 && estimatedOutputBytes >= (inputSizeBytes * NO_GAIN_SIZE_RATIO).toLong()
}
