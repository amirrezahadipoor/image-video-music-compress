package com.compressly.core.engine.estimate

import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PresetDefaults

/**
 * Looks at the file the user just picked and, for every compression grade,
 * estimates the output size. Then it picks one grade as the suggestion.
 *
 * The user can always choose a stronger grade; the suggestion is only a start.
 */
object GradeAdvisor {

    data class Snapshot(
        val estimates: Map<CompressionPreset, Long>,
        val recommended: CompressionPreset
    )

    fun advise(
        mediaType: MediaType,
        info: MediaInfo,
        originalSize: Long,
        sourceMime: String? = null
    ): Snapshot {
        val estimates = LinkedHashMap<CompressionPreset, Long>()
        for (preset in CompressionPreset.ordered) {
            estimates[preset] = estimate(mediaType, info, originalSize, sourceMime, preset)
                .coerceAtLeast(1_000)
        }
        return Snapshot(
            estimates = estimates,
            recommended = recommend(mediaType, info, originalSize, estimates)
        )
    }

    fun estimate(
        mediaType: MediaType,
        info: MediaInfo,
        originalSize: Long,
        sourceMime: String?,
        preset: CompressionPreset
    ): Long = when (mediaType) {
        MediaType.PHOTO -> SizeEstimator.estimatePhoto(
            sourceMime,
            info.effectiveWidth,
            info.effectiveHeight,
            originalSize,
            PresetDefaults.photoSettingsFor(preset)
        )
        MediaType.VIDEO -> SizeEstimator.estimateVideo(
            info,
            PresetDefaults.videoSettingsFor(preset),
            preset
        )
        MediaType.AUDIO -> SizeEstimator.estimateAudio(
            info,
            PresetDefaults.audioSettingsFor(preset)
        )
    }

    fun savingFraction(originalSize: Long, estimatedSize: Long): Double {
        if (originalSize <= 0L) return 0.0
        if (estimatedSize >= originalSize) return 0.0
        return (1.0 - estimatedSize.toDouble() / originalSize).coerceIn(0.0, 0.99)
    }

    private fun recommend(
        mediaType: MediaType,
        info: MediaInfo,
        originalSize: Long,
        estimates: Map<CompressionPreset, Long>
    ): CompressionPreset {
        fun sav(preset: CompressionPreset): Double =
            savingFraction(originalSize, estimates[preset] ?: originalSize)

        val maxSav = sav(CompressionPreset.MAXIMUM_COMPRESSION)
        val highSav = sav(CompressionPreset.HIGH_COMPRESSION)
        val balSav = sav(CompressionPreset.BALANCED)

        // Already tight: suggesting max would only cost quality.
        if (maxSav < 0.10) return CompressionPreset.MAXIMUM_QUALITY

        return when (mediaType) {
            MediaType.VIDEO -> recommendVideo(info, maxSav, highSav, balSav)
            MediaType.PHOTO -> recommendPhoto(info, originalSize, maxSav, highSav, balSav)
            MediaType.AUDIO -> recommendAudio(info, maxSav, highSav, balSav)
        }
    }

    private fun recommendVideo(
        info: MediaInfo,
        maxSav: Double,
        highSav: Double,
        balSav: Double
    ): CompressionPreset {
        val longEdge = maxOf(info.effectiveWidth, info.effectiveHeight)
        val bps = info.videoBitrate
        // 4K or a very fat bitrate: the user picked the file to shrink it.
        if (longEdge >= 2500 || bps >= 25_000_000) {
            return if (maxSav >= 0.40) CompressionPreset.MAXIMUM_COMPRESSION
            else CompressionPreset.HIGH_COMPRESSION
        }
        // Typical phone clip (1080p, 8–20 Mbps): High is the honest default.
        if (longEdge >= 1280 && bps >= 8_000_000 && highSav >= 0.30) {
            return CompressionPreset.HIGH_COMPRESSION
        }
        if (highSav >= 0.45 && highSav - balSav >= 0.12) {
            return CompressionPreset.HIGH_COMPRESSION
        }
        if (balSav >= 0.18) return CompressionPreset.BALANCED
        return CompressionPreset.MAXIMUM_QUALITY
    }

    private fun recommendPhoto(
        info: MediaInfo,
        originalSize: Long,
        maxSav: Double,
        highSav: Double,
        balSav: Double
    ): CompressionPreset {
        val pixels = info.effectiveWidth.toLong() * info.effectiveHeight
        if (originalSize >= 8_000_000 || pixels >= 12_000_000) {
            return if (maxSav >= 0.50) CompressionPreset.HIGH_COMPRESSION
            else CompressionPreset.BALANCED
        }
        if (highSav >= 0.40) return CompressionPreset.HIGH_COMPRESSION
        if (balSav >= 0.20) return CompressionPreset.BALANCED
        return CompressionPreset.MAXIMUM_QUALITY
    }

    private fun recommendAudio(
        info: MediaInfo,
        maxSav: Double,
        highSav: Double,
        balSav: Double
    ): CompressionPreset {
        val kbps = info.audioBitrate / 1000
        // Voice memos and podcasts are already small; don't crush them by default.
        if (kbps in 1..96 || maxSav < 0.15) return CompressionPreset.MAXIMUM_QUALITY
        if (kbps >= 256 && highSav >= 0.30) return CompressionPreset.HIGH_COMPRESSION
        if (balSav >= 0.18) return CompressionPreset.BALANCED
        return CompressionPreset.MAXIMUM_QUALITY
    }
}
