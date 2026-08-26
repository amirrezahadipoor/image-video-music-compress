package com.compressly.core.engine.estimate

import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoResolution
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
        return (heuristic * 1.15).toLong().coerceAtLeast(1_000)
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
        val audioBitrate = when (settings.audioMode) {
            com.compressly.core.engine.model.VideoAudioMode.STRIP -> 0
            com.compressly.core.engine.model.VideoAudioMode.COMPRESS ->
                ((info.audioBitrate * 0.55).toInt()).coerceIn(96_000, 192_000)
            com.compressly.core.engine.model.VideoAudioMode.KEEP ->
                info.audioBitrate.takeIf { it > 0 } ?: 128_000
        }
        // bitrates are bits/second; duration in milliseconds -> bytes.
        val durationMs = trimmedDurationMs(info.durationMs, settings)
        val bytes = (targetVideoBitrate + audioBitrate).toLong() * durationMs / 8L / 1000L
        return bytes.coerceAtLeast(8_000)
    }

    fun targetVideoBitrate(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Int {
        settings.bitrate?.let { return it }
        if (preset == CompressionPreset.SMART) {
            return smartVideoBitrate(
                info.effectiveWidth, info.effectiveHeight,
                settings.frameRate ?: 30, settings.codec
            )
        }
        val source = info.videoBitrate
            .takeIf { it > 0 }
            ?: estimateSourceBitrate(info.width, info.height, info.durationMs)
        val factor = PresetDefaults.videoDefaults[preset]?.bitrateFactor ?: 0.6
        var bitrate = (source * factor).toInt()
        // Resolution scaling
        val resFactor = when (settings.resolution) {
            VideoResolution.ORIGINAL -> 1.0
            VideoResolution.R1080 -> areaFactor(info.effectiveWidth, info.effectiveHeight, 1920, 1080)
            VideoResolution.R720 -> areaFactor(info.effectiveWidth, info.effectiveHeight, 1280, 720)
            VideoResolution.R480 -> areaFactor(info.effectiveWidth, info.effectiveHeight, 854, 480)
            VideoResolution.CUSTOM ->
                areaFactor(info.effectiveWidth, info.effectiveHeight, settings.customWidth, settings.customHeight)
        }
        bitrate = (bitrate * resFactor).toInt()
        if (settings.codec == VideoCodec.H265) bitrate = (bitrate * 0.62).toInt()
        // Sanity bounds: 250 kbps .. 40 Mbps
        return bitrate.coerceIn(250_000, 40_000_000)
    }

    /**
     * Quality-aware bitrate for Smart video mode. Uses a per-pixel bits
     * factor tuned to keep perceptual quality comfortably above 70% while
     * still shrinking the file a lot versus typical phone recordings.
     */
    fun smartVideoBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec): Int {
        if (width <= 0 || height <= 0) return 4_000_000
        val pixels = width.toLong() * height
        val bpp = 0.085
        var bitrate = (pixels * fps.coerceIn(1, 60) * bpp).toInt()
        if (codec == VideoCodec.H265) bitrate = (bitrate * 0.6).toInt()
        return bitrate.coerceIn(500_000, 16_000_000)
    }

    private fun areaFactor(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Double {
        if (srcW <= 0 || srcH <= 0) return 1.0
        val srcArea = srcW.toDouble() * srcH
        val dstArea = dstW.toDouble() * dstH
        return if (dstArea >= srcArea) 1.0 else dstArea / srcArea
    }

    private fun estimateSourceBitrate(w: Int, h: Int, durationMs: Long): Int {
        if (w <= 0 || h <= 0) return 4_000_000
        val pixels = w * h
        val perPixelBits = 0.12
        return (pixels * perPixelBits * 30).toInt().coerceIn(1_000_000, 20_000_000)
    }

    private fun trimmedDurationMs(durationMs: Long, settings: VideoSettings): Long {
        if (!settings.trimEnabled || durationMs <= 0) return durationMs
        val start = settings.trimStartMs.coerceAtLeast(0)
        val end = settings.trimEndMs.takeIf { it > start } ?: durationMs
        return (end - start).coerceAtLeast(0)
    }

    // ---- Audio ----------------------------------------------------------

    fun estimateAudio(durationMs: Long, settings: AudioSettings): Long {
        if (durationMs <= 0) return 0L
        val bitrate = settings.bitrate.coerceIn(32, 320)
        val factor = if (settings.bitrateMode == com.compressly.core.engine.model.AudioBitrateMode.VBR) 0.92 else 1.0
        // bitrate in kbps, duration in ms -> bytes.
        val bytes = durationMs * bitrate / 8L
        return (bytes * factor).toLong().coerceAtLeast(2_000)
    }

    // ---- Preset-only helpers for the gauge ------------------------------

    fun estimatedSavingRange(preset: CompressionPreset, mediaType: com.compressly.core.engine.model.MediaType): Pair<Int, Int> =
        PresetDefaults.reductionRange(preset, mediaType)
}
