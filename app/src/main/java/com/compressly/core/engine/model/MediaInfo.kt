package com.compressly.core.engine.model


/** Lightweight metadata about a media file, read via MediaMetadataRetriever. */
data class MediaInfo(
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Video rotation in degrees (0/90/180/270). */
    val rotation: Int = 0,
    val durationMs: Long = 0L,
    /** Video track bitrate in bps (0 when unknown). */
    val videoBitrate: Int = 0,
    /** Audio track bitrate in bps (0 when unknown). */
    val audioBitrate: Int = 0,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
) {
    val effectiveWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val effectiveHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
}

/** Tags plus embedded cover art, for audio metadata preservation. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val track: String? = null,
    val comment: String? = null,
    /** Embedded artwork as JPEG/PNG bytes (may be null). */
    val artwork: ByteArray? = null
)
