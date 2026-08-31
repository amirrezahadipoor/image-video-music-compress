package com.compressly.core.engine.model

/**
 * Concrete parameters for every tier of the grading system, plus the honest
 * estimated size-reduction range shown to the user before they commit.
 * These values are shared by the engines and the estimators so the estimate
 * shown in the UI always matches what the encoder will actually do.
 */
object PresetDefaults {

    // ---- Photos ---------------------------------------------------------

    data class PhotoDefaults(
        val quality: Int,
        val resize: PhotoResize,
        val customMaxWidth: Int,
        val reductionMin: Int,
        val reductionMax: Int
    )

    val photoDefaults: Map<CompressionPreset, PhotoDefaults> = mapOf(
        CompressionPreset.MAXIMUM_QUALITY to PhotoDefaults(95, PhotoResize.NONE, 0, 0, 15),
        CompressionPreset.BALANCED to PhotoDefaults(82, PhotoResize.NONE, 0, 35, 60),
        CompressionPreset.HIGH_COMPRESSION to PhotoDefaults(62, PhotoResize.R1920, 0, 65, 80),
        CompressionPreset.MAXIMUM_COMPRESSION to PhotoDefaults(40, PhotoResize.R1280, 0, 82, 92),
        // Smart: adaptive quality 85 → 78 → 72 (never below 72) with a 1920
        // cap for large images — the engine promised "quality above 70%" and
        // the old ladder down to 65 silently broke it.
        CompressionPreset.SMART to PhotoDefaults(85, PhotoResize.R1920, 0, 30, 85)
    )

    // ---- Videos ---------------------------------------------------------

    data class VideoDefaults(
        /** Multiplier applied to the source bitrate before resolution scaling. */
        val bitrateFactor: Double,
        /**
         * Bits per pixel per frame this tier is willing to spend, as a ceiling.
         *
         * A share of the source rate alone is arbitrary: a badly encoded 20 Mbps
         * clip and a clean 4 Mbps one at the same resolution got wildly
         * different targets from the same tier, and a bloated source was never
         * squeezed below its own bloat. This ceiling is content-independent, so
         * the tier means the same thing whatever it is fed. The final target is
         * the lower of the two, i.e. always at least as aggressive as before.
         */
        val bpp: Double,
        val resolution: VideoResolution,
        val frameRate: Int?,
        /**
         * What the tier does with the audio track. Every tier used to be KEEP,
         * so "maximum compression" left a 128 kbps stereo track untouched next
         * to a video squeezed down to ~1 Mbps - the audio alone was over 10% of
         * the output and put a hard floor under how small the file could get.
         */
        val audioMode: VideoAudioMode,
        /** Target for the audio track when [audioMode] is COMPRESS, in kbps. */
        val audioKbps: Int,
        val reductionMin: Int,
        val reductionMax: Int
    )

    val videoDefaults: Map<CompressionPreset, VideoDefaults> = mapOf(
        // Audio: the two aggressive tiers re-encode the soundtrack, because at
        // their video rates the original track is a large share of the file.
        // Capped at the source rate by VideoPlanner, so this can never inflate.
        CompressionPreset.MAXIMUM_QUALITY to
            VideoDefaults(0.9, 0.13, VideoResolution.ORIGINAL, null, VideoAudioMode.KEEP, 192, 5, 20),
        CompressionPreset.BALANCED to
            VideoDefaults(0.6, 0.080, VideoResolution.ORIGINAL, null, VideoAudioMode.KEEP, 192, 30, 50),
        CompressionPreset.HIGH_COMPRESSION to
            VideoDefaults(0.35, 0.050, VideoResolution.R1080, null, VideoAudioMode.COMPRESS, 96, 55, 72),
        CompressionPreset.MAXIMUM_COMPRESSION to
            VideoDefaults(0.18, 0.022, VideoResolution.R720, 24, VideoAudioMode.COMPRESS, 64, 75, 92),
        // Smart sits between Balanced and High Compression: it is the default,
        // and its promise is to compress as hard as it can while staying above
        // ~70% perceptual quality. At 0.085 it was ABOVE Balanced's 0.080, so
        // the default mode compressed less than the tier below it - measured on
        // a 1080p30 clip, Smart asked for 5248 kbps and Balanced for 4939.
        CompressionPreset.SMART to
            VideoDefaults(0.55, 0.062, VideoResolution.ORIGINAL, null, VideoAudioMode.KEEP, 128, 30, 85)
    )

    // ---- Audio ----------------------------------------------------------

    data class AudioDefaults(
        val bitrateKbps: Int,
        val reductionMin: Int,
        val reductionMax: Int
    )

    val audioDefaults: Map<CompressionPreset, AudioDefaults> = mapOf(
        CompressionPreset.MAXIMUM_QUALITY to AudioDefaults(320, 5, 20),
        CompressionPreset.BALANCED to AudioDefaults(192, 35, 55),
        CompressionPreset.HIGH_COMPRESSION to AudioDefaults(128, 55, 70),
        CompressionPreset.MAXIMUM_COMPRESSION to AudioDefaults(64, 75, 90),
        // Smart: 192 kbps - perceptually transparent for most music.
        CompressionPreset.SMART to AudioDefaults(192, 30, 85)
    )

    fun reductionRange(preset: CompressionPreset, mediaType: MediaType): Pair<Int, Int> {
        // Safe access: every preset has an entry; the ?: fallback guards against
        // future presets being added without a corresponding defaults entry.
        return when (mediaType) {
            MediaType.PHOTO -> photoDefaults[preset]?.let { it.reductionMin to it.reductionMax } ?: (0 to 50)
            MediaType.VIDEO -> videoDefaults[preset]?.let { it.reductionMin to it.reductionMax } ?: (0 to 50)
            MediaType.AUDIO -> audioDefaults[preset]?.let { it.reductionMin to it.reductionMax } ?: (0 to 50)
        }
    }

    /** Default advanced settings for a given preset, used when the user opens a new job. */
    fun photoSettingsFor(preset: CompressionPreset): PhotoSettings {
        val d = photoDefaults[preset] ?: photoDefaults[CompressionPreset.BALANCED]!!
        return PhotoSettings(
            quality = d.quality,
            resize = d.resize,
            customMaxWidth = d.customMaxWidth,
            preserveMetadata = true,
            smart = preset == CompressionPreset.SMART
        )
    }

    fun videoSettingsFor(preset: CompressionPreset): VideoSettings = videoSettingsFor(preset, null)

    /**
     * Video settings for a compression level.
     *
     * The level owns the resolution, the frame rate, the bitrate and the audio
     * mode; everything else it does not define is carried over from [keepFrom]. Picking a level
     * used to build a blank VideoSettings, which silently reverted H.265 back to
     * H.264, wiped a custom size and threw away the trim window the user had
     * just set. A manual bitrate is deliberately cleared: the level now owns
     * the rate, and a stale one would override it completely.
     */
    fun videoSettingsFor(preset: CompressionPreset, keepFrom: VideoSettings?): VideoSettings {
        val d = videoDefaults[preset] ?: videoDefaults[CompressionPreset.BALANCED]!!
        return VideoSettings(
            resolution = d.resolution,
            customWidth = keepFrom?.customWidth ?: 1280,
            customHeight = keepFrom?.customHeight ?: 720,
            bitrate = null,
            frameRate = d.frameRate,
            codec = keepFrom?.codec ?: VideoCodec.H264,
            audioMode = d.audioMode,
            trimEnabled = keepFrom?.trimEnabled ?: false,
            trimStartMs = keepFrom?.trimStartMs ?: 0L,
            trimEndMs = keepFrom?.trimEndMs ?: 0L
        )
    }

    fun audioSettingsFor(preset: CompressionPreset): AudioSettings {
        val d = audioDefaults[preset] ?: audioDefaults[CompressionPreset.BALANCED]!!
        return AudioSettings(
            format = AudioFormat.AAC,
            bitrate = d.bitrateKbps,
            bitrateMode = AudioBitrateMode.CBR,
            preserveMetadata = true
        )
    }
}
