package com.compressly.core.engine.model

/** Output formats for photo compression. */
enum class PhotoFormat {
    /** Re-encode into the source format when possible, otherwise JPEG. */
    SOURCE,
    JPEG,
    WEBP,
    PNG
}

/** Optional resize targets for photos (max width in px, aspect kept). */
enum class PhotoResize(val maxWidth: Int) {
    NONE(0),
    R2560(2560),
    R1920(1920),
    R1280(1280),
    R1024(1024),
    CUSTOM(0)
}

data class PhotoSettings(
    val outputFormat: PhotoFormat = PhotoFormat.SOURCE,
    val quality: Int = 82,
    val resize: PhotoResize = PhotoResize.NONE,
    val customMaxWidth: Int = 1600,
    val preserveMetadata: Boolean = true,
    /** Smart mode: the engine adaptively drops quality (85 → 78 → 72) until
     *  the size target is met, never below ~70% perceptual quality. */
    val smart: Boolean = false
)

/** Resolution choices for video output. */
enum class VideoResolution {
    ORIGINAL,
    R2160,
    R1080,
    R720,
    R480,
    CUSTOM
}

/**
 * Video codecs. H.264 is compatible everywhere and needs no engine support;
 * H.265 is ~40% smaller on newer devices; AV1 is the most efficient of all but
 * still rare on-device, so it is shown only when a hardware/software AV1
 * encoder exists (Software AV1 = c2.android.av1.encoder, shipped on Android 12+
 * and always present on the CI emulator).
 */
enum class VideoCodec { H264, H265, AV1 }

/** What to do with the audio track of a video. */
enum class VideoAudioMode { KEEP, COMPRESS, STRIP }

data class VideoSettings(
    val resolution: VideoResolution = VideoResolution.ORIGINAL,
    val customWidth: Int = 1280,
    val customHeight: Int = 720,
    /** Manual target bitrate in bits/sec. Null means auto (derived from the preset). */
    val bitrate: Int? = null,
    /** Target frame rate. Null means keep the source frame rate. */
    val frameRate: Int? = null,
    val codec: VideoCodec = VideoCodec.H264,
    val audioMode: VideoAudioMode = VideoAudioMode.KEEP,
    val trimEnabled: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    /**
     * Compress to "under this many MB" instead of to a quality tier. When set,
     * VideoPlanner prices the encode so the output (container included) lands
     * at or below this budget; the existing corrective pass then enforces it in
     * the face of hardware-encoder bitrate overshoot. Null = derive from the
     * preset as usual.
     */
    val sizeTargetMb: Int? = null,
    /**
     * Carry the source's colour/HDR metadata (transfer, standard, range) into
     * the output when the encoder accepts it, so an HDR10/HDR10+ clip is not
     * silently flattened to SDR. Only respected when the encoder honours the
     * keys; otherwise the format falls back to the minimal one and the encode
     * still succeeds.
     */
    val preserveHdr: Boolean = true
)

/**
 * Where the compressed output is written. Lets "replace original" truly land in
 * the original's folder, or the user send the result to a new folder instead.
 */
enum class OutputLocation {
    /** Pictures/Movies/Music/Hajmino (the default). */
    DEFAULT,
    /** The same folder as the source; the source is then removed (replace in place). */
    SAME_AS_SOURCE,
    /** A per-job, user-chosen SAF folder. */
    CUSTOM
}

enum class AudioFormat { AAC, MP3 }

/** VBR favors perceptual quality; CBR gives a predictable size. */
enum class AudioBitrateMode { VBR, CBR }

data class AudioSettings(
    val format: AudioFormat = AudioFormat.AAC,
    /** kbps (64..320) for CBR; target quality anchor for VBR. */
    val bitrate: Int = 192,
    val bitrateMode: AudioBitrateMode = AudioBitrateMode.CBR,
    val preserveMetadata: Boolean = true
)
