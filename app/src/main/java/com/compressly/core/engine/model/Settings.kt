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
    /** Smart mode: the engine adaptively drops quality (85 -> 65) until the
     *  size target is met, never below ~70% perceptual quality. */
    val smart: Boolean = false
)

/** Resolution choices for video output. */
enum class VideoResolution {
    ORIGINAL,
    R1080,
    R720,
    R480,
    CUSTOM
}

/** H.264 is compatible everywhere; H.265 is smaller on newer devices. */
enum class VideoCodec { H264, H265 }

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
    val trimEndMs: Long = 0L
)

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
