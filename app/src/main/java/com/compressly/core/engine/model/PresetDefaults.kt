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
        // Smart: adaptive quality 85->65 with a 1920 cap for large images.
        CompressionPreset.SMART to PhotoDefaults(85, PhotoResize.R1920, 0, 30, 85)
    )

    // ---- Videos ---------------------------------------------------------

    data class VideoDefaults(
        /** Multiplier applied to the source bitrate before resolution scaling. */
        val bitrateFactor: Double,
        val resolution: VideoResolution,
        val frameRate: Int?,
        val reductionMin: Int,
        val reductionMax: Int
    )

    val videoDefaults: Map<CompressionPreset, VideoDefaults> = mapOf(
        CompressionPreset.MAXIMUM_QUALITY to VideoDefaults(0.9, VideoResolution.ORIGINAL, null, 5, 20),
        CompressionPreset.BALANCED to VideoDefaults(0.6, VideoResolution.ORIGINAL, null, 30, 50),
        CompressionPreset.HIGH_COMPRESSION to VideoDefaults(0.35, VideoResolution.R1080, null, 55, 72),
        CompressionPreset.MAXIMUM_COMPRESSION to VideoDefaults(0.2, VideoResolution.R720, 30, 75, 88),
        // Smart: bitrate chosen by a quality-aware formula (see SizeEstimator).
        CompressionPreset.SMART to VideoDefaults(0.0, VideoResolution.ORIGINAL, null, 30, 85)
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
     * The level owns the resolution, the frame rate and the bitrate; everything
     * else it does not define is carried over from [keepFrom]. Picking a level
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
            audioMode = keepFrom?.audioMode ?: VideoAudioMode.KEEP,
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
