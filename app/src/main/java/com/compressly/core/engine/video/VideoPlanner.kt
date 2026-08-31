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
     * Smart resolution ladder, best first. Longer edges are offered when the
     * bits-per-pixel budget says the content can actually afford them, so a
     * clean slow 4K clip stays 4K while a noisy 4K pan gets 1080p — the same
     * perceptual floor at the resolution the bitrate deserves.
     */
    val SMART_EDGE_LADDER = intArrayOf(1920, 1280, 854)

    /**
     * Perceptual floor for "Smart stays above ~70 %": the smallest
     * bits-per-pixel it will accept (H.264 measured against typical phone
     * encodes; HEVC needs ~55 % of that). Below this, blocking is visible
     * before the size target is reached — this is what pushes Smart down a
     * resolution step instead.
     */
    const val WATCHABLE_BPP_H264 = 0.045
    const val WATCHABLE_BPP_H265 = 0.026

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
        val source = info.frameRate.takeIf { it > 0 }
        val requested = settings.frameRate ?: source ?: 30
        // An encoder cannot invent frames. Asking for 30 on a 24 fps film - which
        // is exactly what the MAXIMUM_COMPRESSION tier does - used to configure
        // the rate controller for 25% more frames than it ever received, so the
        // output missed its target. When the source rate is unknown there is
        // nothing to clamp against and the request stands.
        val effective = if (source != null) minOf(requested, source) else requested
        return effective.coerceIn(1, 240)
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
        // SMART-RES-FIX: the smart edge above is only the hard ceiling. When the
        // complexity budget says the content cannot afford that resolution,
        // Smart steps DOWN the ladder on its own — that is the whole promise:
        // the strongest compression that keeps perceptual quality above ~70 %.
        if (preset == CompressionPreset.SMART && settings.resolution == VideoResolution.ORIGINAL) {
            val chosenEdge = smartResolutionEdge(info, settings)
            val displayEdge = maxOf(displayW, displayH)
            if (displayEdge > chosenEdge) {
                val (w, h) = longEdgeCap(chosenEdge, displayW, displayH)
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
            // Smart needs the source-share brake too. Without it the only thing
            // holding Smart back was the 97% no-gain cap, so on an already
            // efficient clip it re-encoded at almost the source rate - a whole
            // generation of loss for a few percent - while Balanced, which does
            // have the brake, came out smaller. That inversion is what made the
            // default mode weaker than the tier below it.
            val share = source * (PresetDefaults.videoDefaults[preset]?.bitrateFactor ?: 0.55)
            // ANALYSIS-FIX: the base bpp is a content-neutral quality anchor.
            // Real footage is not content-neutral — a fast pan needs more bits
            // than a talking head at the same perceived quality — so the Smart
            // target is scaled by the measured complexity of THIS file. Still
            // capped by the source share: analysis can never inflate a file.
            val analysed = smartBitrate(
                outW, outH, fps, settings.codec, info.complexity
            )
            minOf(analysed, share.toInt())
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
    fun smartBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec): Int =
        smartBitrate(width, height, fps, codec, complexity = -1f)

    /**
     * Content-aware Smart target. [complexity] is the measured 0..1 score of
     * THIS file (from ComplexityAnalyzer); -1 means "not analysed" and behaves
     * exactly like the plain [smartBitrate] — analysis is purely additive.
     */
    fun smartBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec, complexity: Float): Int {
        if (width <= 0 || height <= 0) return 4_000_000
        val bpp = PresetDefaults.videoDefaults[CompressionPreset.SMART]?.bpp ?: 0.062
        val complexityFactor = if (complexity >= 0f)
            com.compressly.core.engine.analysis.ComplexityMath.bitrateFactor(complexity)
        else 1f
        var bitrate = (qualityTarget(width.toLong() * height, fps, bpp) * complexityFactor).toInt()
        if (codec == VideoCodec.H265) bitrate = (bitrate * H265_EFFICIENCY).toInt()
        return bitrate.coerceIn(SMART_MIN_BITRATE, 16_000_000)
    }

    /**
     * The long edge Smart actually encodes at, when the user left resolution
     * on ORIGINAL.
     *
     * Decision: for each rung of the ladder, how many bits would the encoder
     * really get there (Smart bpp budget × measured complexity, capped by the
     * source-share brake), versus how many bits that resolution needs to stay
     * above the watchable bpp floor. The largest rung whose budget covers its
     * floor is used. Result:
     *
     *  - a slow, carefully-shot 4K clip stays 4K-capable at 1920 (it can pay
     *    the 1080p floor easily) — exactly the old fixed cap;
     *  - a noisy, heavy-motion 4K clip whose source is only 12 Mbps gets 720p
     *    or 480p, because paying 2.8 Mbps for 1080p would demolish the budget
     *    and produce blocks — the old code kept 1080p and made it blocky;
     *  - un-analysed files take the complexity-neutral path and behave exactly
     *    like the old fixed 1920 cap.
     */
    fun smartResolutionEdge(info: MediaInfo, settings: VideoSettings): Int {
        val displayEdge = maxOf(info.effectiveWidth, info.effectiveHeight)
        if (displayEdge <= SMART_MAX_EDGE) return SMART_MAX_EDGE
        val fps = (settings.frameRate ?: info.frameRate.takeIf { it > 0 } ?: 30).coerceIn(1, 240)
        val displayW = info.effectiveWidth
        val displayH = info.effectiveHeight
        val floor = if (settings.codec == VideoCodec.H265) WATCHABLE_BPP_H265 else WATCHABLE_BPP_H264
        // The same source-share brake targetVideoBitrate applies afterwards.
        val sourceShare = effectiveSourceBitrate(info) * 0.55

        for (edge in SMART_EDGE_LADDER) {
            val (w, h) = dimsAtLongEdge(edge, displayW, displayH)
            val pixels = w.toLong() * h
            // What the encoder will really be told at this rung.
            val affordable = minOf(
                smartBitrate(w, h, fps, settings.codec, info.complexity),
                sourceShare.toInt()
            )
            // What this resolution needs to stay above the watchable floor.
            val needed = qualityTarget(pixels, fps, floor)
            if (affordable >= needed) return edge
        }
        return SMART_EDGE_LADDER.last()
    }

    /** Scales stored-orientation dimensions down to a given long edge (aspect kept). */
    private fun dimsAtLongEdge(edge: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val long = maxOf(displayW, displayH).coerceAtLeast(1)
        val scale = edge.toDouble() / long
        val w = ((displayW * scale).roundToInt() - ((displayW * scale).roundToInt() % 16)).coerceAtLeast(16)
        val h = ((displayH * scale).roundToInt() - ((displayH * scale).roundToInt() % 16)).coerceAtLeast(16)
        return w to h
    }

    /** H.265 needs roughly this share of the H.264 bits for the same quality. */
    const val H265_EFFICIENCY = 0.62

    /**
     * Bits/second that holds a given bits-per-pixel-per-frame quality at a size
     * and rate.
     *
     * The frame-rate term is sub-linear on purpose. Perceptual quality at a
     * fixed bpp does not need twice the bits just because the clip is 60 fps
     * instead of 30 - successive frames are highly redundant, so real encoders
     * get roughly fps^0.75. Pricing 60 fps linearly is what made Smart ask for
     * 10.5 Mbps on an ordinary 1080p60 phone clip. Normalised so a 30 fps clip
     * is priced exactly at pixels * 30 * bpp, which keeps the per-tier bpp
     * ladder readable.
     */
    fun qualityTarget(pixels: Long, fps: Int, bpp: Double): Int {
        val rate = fps.coerceIn(1, 240)
        val fpsFactor = Math.pow(rate / 30.0, 0.75)
        return (pixels * 30 * bpp * fpsFactor).toInt()
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
        val defaults = PresetDefaults.videoDefaults[preset]
        val factor = defaults?.bitrateFactor ?: 0.6
        val bpp = defaults?.bpp ?: 0.080
        val fps = resolvedFps(settings, info)

        val srcArea = info.effectiveWidth.toLong() * info.effectiveHeight.coerceAtLeast(1)
        val outArea = outW.toLong() * outH.coerceAtLeast(1)

        // (a) A share of what the source carries, scaled by the pixel reduction.
        var fromSource = source * factor
        if (srcArea > 0 && outArea < srcArea) fromSource *= outArea.toDouble() / srcArea

        // (b) A content-independent ceiling: what this tier is willing to spend
        // per pixel per frame at the size and rate actually being written,
        // through the same sub-linear frame-rate model Smart uses.
        val ceiling = qualityTarget(outArea, fps, bpp).toDouble()

        // The lower of the two. min() means this can only ever be more
        // aggressive than the old source-share rule, never less - the ceiling
        // is what finally squeezes a bloated source instead of reproducing its
        // bloat at 60%.
        var bitrate = minOf(fromSource, ceiling)
        if (settings.codec == VideoCodec.H265) bitrate *= H265_EFFICIENCY
        return bitrate.toInt()
    }

    /**
     * Decides which decoded frames to render when the rate is being reduced.
     *
     * The schedule is absolute - frame n is due at firstPts + n * interval - and
     * deliberately does NOT measure from the last frame that was kept. Doing
     * that re-anchored the gap to a frame which was already late, so a 30 fps
     * source asked for 24 fps dropped every other frame and delivered roughly
     * 15 fps. Anchoring to the ideal timeline gives 25 fps from the same source,
     * which is what "24" is supposed to mean on material that has no 24 fps
     * frames to begin with.
     */
    class FrameGate(intervalUs: Long) {
        private val intervalUs = intervalUs.coerceAtLeast(1L)
        private var nextDueUs = Long.MIN_VALUE

        /** True when the frame timestamped [ptsUs] should be kept. */
        fun shouldKeep(ptsUs: Long): Boolean {
            if (nextDueUs == Long.MIN_VALUE) {
                nextDueUs = ptsUs + intervalUs
                return true
            }
            if (ptsUs < nextDueUs) return false
            // Catch the schedule up after an irregular gap so a burst of frames
            // is not let through immediately afterwards.
            while (nextDueUs <= ptsUs) nextDueUs += intervalUs
            return true
        }

        /** Frames kept out of [count] evenly spaced source frames. */
        fun keptOutOf(sourceFps: Int, targetFps: Int, count: Int): Int {
            var kept = 0
            val stepUs = 1_000_000L / sourceFps.coerceAtLeast(1)
            for (i in 0 until count) if (shouldKeep(i * stepUs)) kept++
            return kept
        }
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

    /**
     * How far over target a first pass may land before it is worth redoing.
     * 1.30 (not 1.15): on most phones the hardware encoder overshoots VBR by
     * 10-25% routinely, and re-encoding every such file doubles the job's
     * time and battery for a few per cent. Only a clearly bloated first pass
     * pays for a second full decode+encode.
     */
    const val OVERSHOOT_TOLERANCE = 1.30

    /** Never correct below this share of the target, however bad the first pass was. */
    const val MIN_CORRECTION_RATIO = 0.45

    /**
     * Below this, the measurement is not trustworthy. A short clip carries the
     * same few kilobytes of container overhead as a long one, so bytes/duration
     * overstates its rate badly enough to trigger a pointless re-encode.
     */
    const val MIN_CORRECTABLE_DURATION_MS = 1_500L
    const val MIN_CORRECTABLE_BYTES = 64_000L

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
    fun correctedBitrate(targetBitrate: Int, actualBytes: Long, durationMs: Long): Int? =
        correctedBitrate(targetBitrate, actualBytes, durationMs, aggressive = false)

    fun correctedBitrate(
        targetBitrate: Int,
        actualBytes: Long,
        durationMs: Long,
        aggressive: Boolean
    ): Int? {
        if (targetBitrate <= 0) return null
        if (durationMs < MIN_CORRECTABLE_DURATION_MS) return null
        if (actualBytes < MIN_CORRECTABLE_BYTES) return null
        val actual = measuredBitrate(actualBytes, durationMs)
        if (actual <= 0) return null
        // Aggressive tiers run CBR, which overshoots far less than VBR, so
        // even a modest 15% overshoot there is worth one correction pass —
        // their whole promise is "as small as possible".
        val tolerance = if (aggressive) 1.15 else OVERSHOOT_TOLERANCE
        if (actual <= (targetBitrate * tolerance).toLong()) return null
        val corrected = (targetBitrate.toLong() * targetBitrate / actual).toInt()
        val minRatio = if (aggressive) 0.32 else MIN_CORRECTION_RATIO
        val floor = (targetBitrate * minRatio).toInt()
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
        val iFrameInterval: Int,
        /**
         * Aggressive tiers ask the encoder for CBR so KEY_BIT_RATE is a
         * budget, not a VBR hint the SoC is free to ignore.
         */
        val preferCbr: Boolean = false,
        /** Tighter overshoot tolerance + deeper correction floor. */
        val aggressiveCorrection: Boolean = false
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
        val aggressive = preset == CompressionPreset.MAXIMUM_COMPRESSION ||
            preset == CompressionPreset.HIGH_COMPRESSION
        return Plan(
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            dropFrames = dropsFrames(settings, info),
            iFrameInterval = iFrameIntervalSeconds(bitrate, w, h, fps),
            preferCbr = aggressive,
            aggressiveCorrection = preset == CompressionPreset.MAXIMUM_COMPRESSION
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
     * MP4 still carries a moov atom and interleave overhead on top of the
     * elementary streams. Pricing only the bitrates made the live estimate
     * sit a few percent under the file the muxer actually wrote.
     */
    const val CONTAINER_OVERHEAD_RATIO = 1.03
    const val CONTAINER_OVERHEAD_BYTES = 24_000L

    fun estimatedFileBytes(payloadBytes: Long): Long {
        val payload = payloadBytes.coerceAtLeast(1L)
        return (payload * CONTAINER_OVERHEAD_RATIO).toLong()
            .plus(CONTAINER_OVERHEAD_BYTES)
            .coerceAtLeast(8_000L)
    }

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
        // NOOP-FIX: a codec request (e.g. H.264 -> H.265) is a real change and
        // must never be skipped as "nothing to do". Before this, picking H.265
        // for an untouched-resolution clip could silently return the original
        // (copy) without any re-encode when the estimate was close to the
        // source size — the user's explicit codec choice was ignored.
        val sourceMime = info.mimeType?.lowercase() ?: ""
        val sourceIsHevc = sourceMime.contains("hevc")
        if (settings.codec == VideoCodec.H265 && !sourceIsHevc) return false
        if (settings.codec == VideoCodec.H264 && sourceIsHevc) return false
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
    fun iFrameIntervalSeconds(targetBitrate: Int, width: Int, height: Int): Int =
        iFrameIntervalSeconds(targetBitrate, width, height, fps = 30)

    fun iFrameIntervalSeconds(targetBitrate: Int, width: Int, height: Int, fps: Int): Int {
        val pixels = (width.toLong() * height).coerceAtLeast(1)
        val rate = fps.coerceIn(1, 240)
        // Roughly "how many bits does one frame get" — at the REAL rate the
        // encoder is driven at. FIX: a fixed 30 fps here budgeted 60 fps
        // footage as if each frame were twice as fat, so 1080p60 always chose
        // a 2 s GOP even at rates where a 4 s one was the honest fit.
        val bitsPerFrame = targetBitrate.toDouble() / rate
        return when {
            bitsPerFrame / pixels < 0.02 -> 5
            bitsPerFrame / pixels < 0.04 -> 4
            else -> 2
        }
    }
}
