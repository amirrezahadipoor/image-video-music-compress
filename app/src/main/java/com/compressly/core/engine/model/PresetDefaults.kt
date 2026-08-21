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
        CompressionPreset.MAXIMUM_COMPRESSION to PhotoDefaults(40, PhotoResize.R1280, 0, 82, 92)
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
        CompressionPreset.MAXIMUM_COMPRESSION to VideoDefaults(0.2, VideoResolution.R720, 30, 75, 88)
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
        CompressionPreset.MAXIMUM_COMPRESSION to AudioDefaults(64, 75, 90)
    )

    fun reductionRange(preset: CompressionPreset, mediaType: MediaType): Pair<Int, Int> = when (mediaType) {
        MediaType.PHOTO -> photoDefaults[preset]!!.let { it.reductionMin to it.reductionMax }
        MediaType.VIDEO -> videoDefaults[preset]!!.let { it.reductionMin to it.reductionMax }
        MediaType.AUDIO -> audioDefaults[preset]!!.let { it.reductionMin to it.reductionMax }
    }

    /** Default advanced settings for a given preset, used when the user opens a new job. */
    fun photoSettingsFor(preset: CompressionPreset): PhotoSettings {
        val d = photoDefaults[preset]!!
        return PhotoSettings(
            quality = d.quality,
            resize = d.resize,
            customMaxWidth = d.customMaxWidth,
            preserveMetadata = true
        )
    }

    fun videoSettingsFor(preset: CompressionPreset): VideoSettings {
        val d = videoDefaults[preset]!!
        return VideoSettings(
            resolution = d.resolution,
            frameRate = d.frameRate,
            audioMode = VideoAudioMode.KEEP,
            trimEnabled = false
        )
    }

    fun audioSettingsFor(preset: CompressionPreset): AudioSettings {
        val d = audioDefaults[preset]!!
        return AudioSettings(
            format = AudioFormat.AAC,
            bitrate = d.bitrateKbps,
            bitrateMode = AudioBitrateMode.CBR,
            preserveMetadata = true
        )
    }
}
