package com.compressly.core.engine.estimate

import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoSettings

/**
 * Estimates output bytes BEFORE compression so the UI can show real numbers
 * (original size, estimated size, estimated saving) before the user commits.
 *
 * Estimates are honest approximations; the result screen always shows the
 * actual measured numbers afterwards.
 */
object SizeEstimator {

    // ---- Photos ---------------------------------------------------------

    fun estimatePhoto(
        sourceMime: String?,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceSizeBytes: Long,
        settings: PhotoSettings
    ): Long {
        if (sourceWidth <= 0 || sourceHeight <= 0) return estimateByScale(sourceSizeBytes, reductionScale(settings))
        var w = sourceWidth.toLong()
        var h = sourceHeight.toLong()
        val maxW = when (settings.resize) {
            PhotoResize.NONE -> Long.MAX_VALUE
            PhotoResize.CUSTOM -> settings.customMaxWidth.toLong().coerceAtLeast(1)
            else -> settings.resize.maxWidth.toLong()
        }
        if (w > maxW) {
            val ratio = maxW.toDouble() / w
            w = maxW
            h = (h * ratio).toLong()
        }
        val pixels = (w * h).coerceAtLeast(1)

        val format = resolvedFormat(sourceMime, settings.outputFormat)
        // Smart mode targets ~85% then adapts; estimate at that anchor.
        val quality = (if (settings.smart) 85 else settings.quality).coerceIn(1, 100)
        val bytesPerPixel = when (format) {
            "png" -> 2.6 // PNG is lossless; roughly 8-12 bits/px for photos
            // WebP lossy is ~30% smaller than JPEG at the same quality setting.
            // Real-world bpp for WebP lossy ≈ 0.07; JPEG ≈ 0.10.
            "webp" -> 0.07 * (quality / 82.0)
            else -> 0.10 * (quality / 82.0) // JPEG heuristic
        }
        // Baseline estimate, then blend toward a sane JPEG real-world result.
        val raw = pixels * bytesPerPixel
        val heuristic = if (format == "png") raw else raw.coerceAtLeast(pixels / 10.0)
        val estimated = (heuristic * 1.15).toLong().coerceAtLeast(1_000)
        return if (sourceSizeBytes > 0) estimated.coerceAtMost(sourceSizeBytes) else estimated
    }

    private fun resolvedFormat(sourceMime: String?, format: PhotoFormat): String = when (format) {
        PhotoFormat.PNG -> "png"
        PhotoFormat.WEBP -> "webp"
        PhotoFormat.JPEG -> "jpeg"
        PhotoFormat.SOURCE -> when {
            sourceMime == "image/png" -> "png"
            sourceMime == "image/webp" -> "webp"
            else -> "jpeg"
        }
    }

    private fun reductionScale(settings: PhotoSettings): Double {
        // Relative to a typical 82% quality, no-resize baseline.
        val qualityFactor = (settings.quality / 82.0)
        val resizeFactor = when (settings.resize) {
            PhotoResize.NONE, PhotoResize.CUSTOM -> 1.0
            PhotoResize.R2560 -> 0.72
            PhotoResize.R1920 -> 0.55
            PhotoResize.R1280 -> 0.36
            PhotoResize.R1024 -> 0.26
        }
        val formatFactor = if (settings.outputFormat == PhotoFormat.PNG) 3.2 else 1.0
        return (qualityFactor * resizeFactor * formatFactor).coerceAtLeast(0.1)
    }

    private fun estimateByScale(sourceBytes: Long, scale: Double): Long =
        (sourceBytes * scale).toLong().coerceAtLeast(1_000)

    // ---- Videos ---------------------------------------------------------

    fun estimateVideo(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Long {
        val targetVideoBitrate = targetVideoBitrate(info, settings, preset)
        // Same source as the encoder, so the number shown before pressing
        // "compress" is the number the encoder is actually configured with.
        val audioBitrate = com.compressly.core.engine.video.VideoPlanner
            .audioBitrateBps(info, settings, preset)
        // bitrates are bits/second; duration in milliseconds -> bytes.
        val durationMs = trimmedDurationMs(info.durationMs, settings)
        val bytes = (targetVideoBitrate + audioBitrate).toLong() * durationMs / 8L / 1000L
        return com.compressly.core.engine.video.VideoPlanner.estimatedFileBytes(bytes)
    }

    /**
     * Target video bitrate. Delegates to [com.compressly.core.engine.video.VideoPlanner]
     * so the number shown here is exactly the number the encoder is configured
     * with — including the rule that it never exceeds what the source carries.
     */
    fun targetVideoBitrate(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Int =
        com.compressly.core.engine.video.VideoPlanner.targetVideoBitrate(info, settings, preset)

    /**
     * Quality-aware bitrate for Smart video mode, for a given resolution and
     * frame rate. See [com.compressly.core.engine.video.VideoPlanner.smartBitrate].
     */
    fun smartVideoBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec): Int =
        com.compressly.core.engine.video.VideoPlanner.smartBitrate(width, height, fps, codec)

    private fun trimmedDurationMs(durationMs: Long, settings: VideoSettings): Long {
        if (!settings.trimEnabled || durationMs <= 0) return durationMs
        val start = settings.trimStartMs.coerceAtLeast(0)
        val end = settings.trimEndMs.takeIf { it > start } ?: durationMs
        return (end - start).coerceAtLeast(0)
    }

    // ---- Audio ----------------------------------------------------------

    fun estimateAudio(durationMs: Long, settings: AudioSettings): Long =
        estimateAudio(durationMs, settings, 0)

    /**
     * Size estimate that knows the source rate. Without it the estimate - and
     * the encode - could aim above what the file already carries, which is how a
     * 64 kbps voice memo came back three times larger on the Smart preset.
     */
    fun estimateAudio(info: MediaInfo, settings: AudioSettings): Long =
        estimateAudio(info.durationMs, settings, info.audioBitrate)

    private fun estimateAudio(durationMs: Long, settings: AudioSettings, sourceBitrateBps: Int): Long {
        if (durationMs <= 0) return 0L
        val bitrate = com.compressly.core.engine.audio.AudioPlanner
            .targetBitrateKbps(settings.bitrate, sourceBitrateBps)
        return com.compressly.core.engine.audio.AudioPlanner.estimatedBytes(
            bitrate,
            durationMs,
            vbr = settings.bitrateMode == com.compressly.core.engine.model.AudioBitrateMode.VBR
        )
    }

}
