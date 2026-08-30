package com.compressly.core.engine.video

import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.core.engine.model.VideoAudioMode
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoResolution
import com.compressly.core.engine.model.VideoSettings
import kotlin.math.roundToInt

/**
 * The single source of truth for the numbers a video transcode is planned with:
 * output dimensions, frame rate and target bitrate.
 *
 * It is pure Kotlin (no `android.*` imports) so the JVM unit tests cover it, and
 * it is shared by the live size estimate in the UI and by the real encoder, so
 * what the user is promised and what the encoder is told can never disagree.
 *
 * Why this exists: the estimate and the transcoder used to compute the target
 * bitrate independently, and neither of them looked at how much the source file
 * actually carries. A "720p clip downloaded from a messenger" (1.2 Mbps) got a
 * 2.35 Mbps Smart target and came out **twice as large as it went in**. Every
 * rule that prevents that lives here now, in one place.
 */
object VideoPlanner {

    /** Rate range a hardware encoder can actually honor. */
    const val MIN_BITRATE = 250_000
    const val MAX_BITRATE = 40_000_000

    /**
     * Encoding at the bitrate the source already carries buys nothing — it only
     * adds a generation of loss. Anything above this share of the source rate is
     * treated as "no gain", and the file is left alone instead.
     */
    const val NO_GAIN_RATIO = 0.97

    /**
     * Below this share of the input size an output counts as "not smaller".
     * Re-encoding for a 2-3 % saving costs a whole generation of quality, so the
     * file is left alone instead.
     */
    const val NO_GAIN_SIZE_RATIO = 0.95

    /** Smart mode caps footage on its long edge; beyond that nothing is gained. */
    const val SMART_MAX_EDGE = 1920

    /**
     * The lowest rate Smart will aim for on its own initiative. Higher than
     * [MIN_BITRATE] on purpose: 250 kbps is what the hardware encoder can
     * tolerate, 500 kbps is what Smart considers watchable. The source cap can
     * still pull a target below this - never encoding above the input wins.
     */
    const val SMART_MIN_BITRATE = 500_000

    // ------------------------------------------------------------------
    // Source bitrate
    // ------------------------------------------------------------------

    /**
     * The video-only bitrate of the source, in bits/second.
     *
     * `METADATA_KEY_BITRATE` reports the **whole container** rate, audio track
     * included, so the audio part is subtracted before it is used as a ceiling.
     * Returns 0 when the container carries no usable number, in which case the
     * caller falls back to a per-pixel heuristic.
     */
    fun sourceVideoBitrate(info: MediaInfo): Int {
        val container = info.videoBitrate
        if (container <= 0) return 0
        val audio = info.audioBitrate.coerceAtLeast(0)
        val video = container - audio
        // A bogus audio number must never drive the video target negative.
        return if (video > 0) video else container
    }

    /**
     * Best guess at the source video rate when the container does not report
     * one. Same heuristic the estimator has always used, so estimates stay
     * stable for files with missing metadata.
     */
    fun estimateSourceBitrate(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 4_000_000
        val pixels = width.toLong() * height
        return (pixels * 0.12 * 30).toInt().coerceIn(1_000_000, 20_000_000)
    }

    /** The rate the planner works with: measured, or estimated when unknown. */
    fun effectiveSourceBitrate(info: MediaInfo): Int {
        val measured = sourceVideoBitrate(info)
        return if (measured > 0) measured else estimateSourceBitrate(info.width, info.height)
    }

    // ------------------------------------------------------------------
    // Frame rate
    // ------------------------------------------------------------------

    /**
     * The frame rate the encoder will actually be driven at.
     *
     * The engine used to hardcode 30 whenever the user did not pick a rate, so
     * on 60 fps phone footage the rate controller was budgeting for half the
     * frames it received and the output missed its target. The real source rate
     * is used instead, and only an explicit user choice overrides it.
     */
    fun resolvedFps(settings: VideoSettings, info: MediaInfo): Int {
        val source = info.frameRate.takeIf { it > 0 } ?: 30
        return (settings.frameRate ?: source).coerceIn(1, 240)
    }

    /**
     * Whether frames must be dropped to reach the requested rate. Dropping is
     * only ever needed when the request is *below* the source rate; asking for
     * 60 fps on a 30 fps clip cannot invent frames.
     */
    fun dropsFrames(settings: VideoSettings, info: MediaInfo): Boolean {
        val requested = settings.frameRate ?: return false
        val source = info.frameRate.takeIf { it > 0 } ?: return true
        return requested < source
    }

    // ------------------------------------------------------------------
    // Output dimensions
    // ------------------------------------------------------------------

    /**
     * Output dimensions in the STORED orientation (rotation is applied through
     * the muxer hint), aligned down to a multiple of 16 — video encoders are
     * both faster and more stable on aligned sizes.
     *
     * Smart mode additionally caps very large footage at 1920 px on the long
     * edge: perceptually that keeps quality well above 70 % while saving a lot
     * of space, and it is the reason Smart used to over-allocate bitrate (the
     * old estimator priced the source resolution, the encoder wrote 1080p).
     */
    fun outputDims(
        info: MediaInfo,
        settings: VideoSettings,
        preset: CompressionPreset
    ): Pair<Int, Int> {
        val storedW = info.width.takeIf { it > 0 } ?: return 1280 to 720
        val storedH = info.height.takeIf { it > 0 } ?: return 1280 to 720
        val rotated = info.rotation == 90 || info.rotation == 270
        val displayW = if (rotated) storedH else storedW
        val displayH = if (rotated) storedW else storedH

        var (capW, capH) = when (settings.resolution) {
            VideoResolution.ORIGINAL -> displayW to displayH
            VideoResolution.R1080 -> longEdgeCap(1920, displayW, displayH)
            VideoResolution.R720 -> longEdgeCap(1280, displayW, displayH)
            VideoResolution.R480 -> longEdgeCap(854, displayW, displayH)
            VideoResolution.CUSTOM ->
                settings.customWidth.coerceAtLeast(64) to settings.customHeight.coerceAtLeast(64)
        }
        if (preset == CompressionPreset.SMART) {
            val longEdge = maxOf(displayW, displayH)
            if (longEdge > SMART_MAX_EDGE) {
                val (w, h) = longEdgeCap(SMART_MAX_EDGE, displayW, displayH)
                capW = minOf(capW, w)
                capH = minOf(capH, h)
            }
        }

        val scale = minOf(1.0, capW.toDouble() / displayW, capH.toDouble() / displayH)
        // roundToInt, not toInt: a 1080->720 scale is 0.6666666666666666 in
        // binary floating point, so truncating gave 719 px and then the 16-byte
        // alignment dropped it further to 704. Rounding lands on 720.
        var w = (storedW * scale).roundToInt()
        var h = (storedH * scale).roundToInt()
        // Encoders perform best and are most stable on multiples of 16.
        w -= w % 16
        h -= h % 16
        return w.coerceAtLeast(16) to h.coerceAtLeast(16)
    }

    /**
     * A tier like "720p" means "long edge 1280", not "1280 wide x 720 high":
     * a portrait phone clip must come out 720x1280, not be squashed to 405x720.
     */
    private fun longEdgeCap(longEdge: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val shortEdge = (longEdge * 9.0 / 16.0).toInt()
        return if (displayW >= displayH) longEdge to shortEdge else shortEdge to longEdge
    }

    // ------------------------------------------------------------------
    // Target bitrate
    // ------------------------------------------------------------------

    /**
     * Target video bitrate for a job, in bits/second.
     *
     * The result is always **at or below the rate the source actually carries**
     * (minus a small margin), because a transcode priced above its input can
     * only ever make the file bigger. That single rule is what stops Smart and
     * Maximum-Compression from inflating already-small clips.
     */
    fun targetVideoBitrate(
        info: MediaInfo,
        settings: VideoSettings,
        preset: CompressionPreset
    ): Int {
        // An explicit manual bitrate always wins — the user is in control.
        settings.bitrate?.let { return it.coerceIn(MIN_BITRATE, MAX_BITRATE) }

        val source = effectiveSourceBitrate(info)
        // Sources thinner than the encoder floor cannot be improved on; hand
        // back the source rate so the "no gain" check below can keep the file.
        if (source in 1 until MIN_BITRATE) return source

        val (outW, outH) = outputDims(info, settings, preset)
        val fps = resolvedFps(settings, info)

        val raw = if (preset == CompressionPreset.SMART) {
            smartBitrate(outW, outH, fps, settings.codec)
        } else {
            tierBitrate(info, settings, preset, outW, outH, source)
        }

        val capped = minOf(raw, (source * NO_GAIN_RATIO).toInt())
        return capped.coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    /**
     * Quality-aware Smart target: bits per pixel per frame, priced against the
     * resolution and frame rate the encoder will really write.
     */
    fun smartBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec): Int {
        if (width <= 0 || height <= 0) return 4_000_000
        val pixels = width.toLong() * height
        val bpp = 0.085
        var bitrate = (pixels * fps.coerceIn(1, 240) * bpp).toInt()
        if (codec == VideoCodec.H265) bitrate = (bitrate * 0.6).toInt()
        return bitrate.coerceIn(SMART_MIN_BITRATE, 16_000_000)
    }

    /** Manual tiers: a share of the source rate, scaled by the pixel reduction. */
    private fun tierBitrate(
        info: MediaInfo,
        settings: VideoSettings,
        preset: CompressionPreset,
        outW: Int,
        outH: Int,
        source: Int
    ): Int {
        val factor = PresetDefaults.videoDefaults[preset]?.bitrateFactor ?: 0.6
        var bitrate = source * factor
        val srcArea = info.effectiveWidth.toLong() * info.effectiveHeight.coerceAtLeast(1)
        val outArea = outW.toLong() * outH.coerceAtLeast(1)
        if (srcArea > 0 && outArea < srcArea) bitrate *= outArea.toDouble() / srcArea
        if (settings.codec == VideoCodec.H265) bitrate *= 0.62
        return bitrate.toInt()
    }

    // ------------------------------------------------------------------
    // Audio track of a video
    // ------------------------------------------------------------------

    /** Fallback when a container does not report its audio rate. */
    const val DEFAULT_KEEP_AUDIO_BPS = 128_000

    /**
     * Target rate for the video's audio track, in bits/second. Shared by the
     * encoder and the live estimate so the two cannot disagree - they used to
     * each carry their own copy of the "55% of source, 96-192 kbps" formula.
     *
     * A COMPRESS target is routed through [com.compressly.core.engine.audio.AudioPlanner]
     * so it is capped at the source rate like everything else.
     */
    fun audioBitrateBps(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Int =
        when (settings.audioMode) {
            VideoAudioMode.STRIP -> 0
            VideoAudioMode.KEEP ->
                info.audioBitrate.takeIf { it > 0 } ?: DEFAULT_KEEP_AUDIO_BPS
            VideoAudioMode.COMPRESS -> {
                val requested = PresetDefaults.videoDefaults[preset]?.audioKbps ?: 112
                com.compressly.core.engine.audio.AudioPlanner
                    .targetBitrateKbps(requested, info.audioBitrate) * 1000
            }
        }

    // ------------------------------------------------------------------
    // Bitrate correction
    // ------------------------------------------------------------------

    /** How far over target a first pass may land before it is worth redoing. */
    const val OVERSHOOT_TOLERANCE = 1.15

    /** Never correct below this share of the target, however bad the first pass was. */
    const val MIN_CORRECTION_RATIO = 0.45

    /** Measured rate of an encoded file, in bits/second. */
    fun measuredBitrate(bytes: Long, durationMs: Long): Int {
        if (bytes <= 0 || durationMs <= 0) return 0
        return (bytes * 8_000L / durationMs).toInt()
    }

    /**
     * The rate to use for a corrective second pass, or null when the first pass
     * was close enough to leave alone.
     *
     * Hardware encoders treat KEY_BIT_RATE as a *hint* in VBR mode and routinely
     * overshoot it - 30-100% is common on phone SoCs, and some vendors ignore it
     * altogether. That is the main reason "compress harder" did not actually
     * produce a smaller file: the planner asked for 1.3 Mbps and the encoder
     * happily delivered 3 Mbps. The correction is proportional to the measured
     * overshoot, clamped so one wild pass cannot collapse the next one into
     * unwatchable quality.
     */
    fun correctedBitrate(targetBitrate: Int, actualBytes: Long, durationMs: Long): Int? {
        if (targetBitrate <= 0) return null
        val actual = measuredBitrate(actualBytes, durationMs)
        if (actual <= 0) return null
        if (actual <= (targetBitrate * OVERSHOOT_TOLERANCE).toLong()) return null
        val corrected = (targetBitrate.toLong() * targetBitrate / actual).toInt()
        val floor = (targetBitrate * MIN_CORRECTION_RATIO).toInt()
        return corrected.coerceAtLeast(floor).coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    /** Everything the encoder needs to be configured with, decided in one place. */
    data class Plan(
        val width: Int,
        val height: Int,
        /** Target video bitrate in bits/second. */
        val bitrate: Int,
        /** Frame rate the encoder is driven at. */
        val fps: Int,
        /** Whether frames must be dropped to reach [fps]. */
        val dropFrames: Boolean,
        /** Seconds between key frames. */
        val iFrameInterval: Int
    )

    /**
     * Resolves the full encode plan for a job. The transcoder and the live size
     * estimate both call this, so the number shown to the user before they press
     * "compress" is the number the encoder is actually configured with.
     */
    fun plan(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Plan {
        val (w, h) = outputDims(info, settings, preset)
        val bitrate = targetVideoBitrate(info, settings, preset)
        val fps = resolvedFps(settings, info)
        return Plan(
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            dropFrames = dropsFrames(settings, info),
            iFrameInterval = iFrameIntervalSeconds(bitrate, w, h)
        )
    }

    // ------------------------------------------------------------------
    // "Would this even help?"
    // ------------------------------------------------------------------

    /**
     * True when re-encoding cannot shrink the file, so the original should be
     * kept instead of being decoded and re-encoded for nothing.
     */
    fun shouldKeepOriginal(estimatedOutputBytes: Long, inputSizeBytes: Long): Boolean =
        inputSizeBytes > 0 && estimatedOutputBytes >= (inputSizeBytes * NO_GAIN_SIZE_RATIO).toLong()

    /**
     * True when the planned target is at or above the source rate, i.e. the
     * transcode would only add a generation of loss.
     */
    fun isNoGainTarget(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Boolean {
        val source = sourceVideoBitrate(info)
        if (source <= 0) return false
        return targetVideoBitrate(info, settings, preset) >= (source * NO_GAIN_RATIO).toInt()
    }

    /**
     * True when the job asks for nothing the file does not already have: same
     * resolution, same frame rate, audio untouched, no trim. Combined with
     * [shouldKeepOriginal] this is the "leave the file alone" decision — an
     * explicit resize, re-rate, trim or audio strip is always honoured even if
     * it does not shrink the file, because the user asked for that change.
     */
    fun isNoOpTranscode(info: MediaInfo, settings: VideoSettings, preset: CompressionPreset): Boolean {
        if (settings.trimEnabled) return false
        if (settings.audioMode != VideoAudioMode.KEEP) return false
        if (settings.resolution != VideoResolution.ORIGINAL) return false
        if (dropsFrames(settings, info)) return false
        val (w, h) = outputDims(info, settings, preset)
        if (w != align16(info.width) || h != align16(info.height)) return false
        return true
    }

    private fun align16(value: Int): Int = if (value <= 0) 0 else (value - value % 16).coerceAtLeast(16)

    /**
     * Key-frame interval that fits the target rate. A fixed 2 s interval at a
     * very low bitrate lets single I-frames eat the whole budget, which is why
     * aggressive tiers used to overshoot their size and look blocky.
     */
    fun iFrameIntervalSeconds(targetBitrate: Int, width: Int, height: Int): Int {
        val pixels = (width.toLong() * height).coerceAtLeast(1)
        // Roughly "how many bits does one frame get".
        val bitsPerFrame = targetBitrate.toDouble() / 30.0
        return when {
            bitsPerFrame / pixels < 0.02 -> 5
            bitsPerFrame / pixels < 0.04 -> 4
            else -> 2
        }
    }
}
