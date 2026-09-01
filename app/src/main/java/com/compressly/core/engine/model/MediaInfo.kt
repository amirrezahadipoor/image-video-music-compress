package com.compressly.core.engine.model


/** Lightweight metadata about a media file, read via MediaMetadataRetriever. */
data class MediaInfo(
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Video rotation in degrees (0/90/180/270). */
    val rotation: Int = 0,
    val durationMs: Long = 0L,
    /** Source frame rate in fps (0 when unknown). The encoder is driven at this
     *  rate unless the user explicitly picks one — pricing the encode for a
     *  guessed 30 fps while feeding it 60 made the output miss its target. */
    val frameRate: Int = 0,
    /** Whole-container bitrate in bps, audio included (0 when unknown). */
    val videoBitrate: Int = 0,
    /** Audio track bitrate in bps (0 when unknown). */
    val audioBitrate: Int = 0,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /**
     * Content analysis (0..1): how expensive this footage is to compress well.
     * Together with [motion]/[detail]/[color], filled by ComplexityAnalyzer.
     * -1 means "not analysed" — every planner/heuristic falls back to the
     * neutral 0.5 behaviour, so analysis is purely additive.
     */
    val complexity: Float = -1f,
    val motion: Float = -1f,
    val detail: Float = -1f,
    val color: Float = -1f,
    /** Scene changes found by the content probe (0 when not analysed). */
    val sceneCuts: Int = 0
) {
    val effectiveWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val effectiveHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    val hasComplexity: Boolean get() = complexity >= 0f
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
) {
    // ByteArray uses identity comparison; override for structural equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTags) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && genre == other.genre && year == other.year &&
            track == other.track && comment == other.comment &&
            (artwork == null && other.artwork == null || artwork != null && other.artwork != null && artwork.contentEquals(other.artwork))
    }

    override fun hashCode(): Int {
        var h = 0
        h = 31 * h + (title?.hashCode() ?: 0)
        h = 31 * h + (artist?.hashCode() ?: 0)
        h = 31 * h + (album?.hashCode() ?: 0)
        h = 31 * h + (albumArtist?.hashCode() ?: 0)
        h = 31 * h + (genre?.hashCode() ?: 0)
        h = 31 * h + (year?.hashCode() ?: 0)
        h = 31 * h + (track?.hashCode() ?: 0)
        h = 31 * h + (comment?.hashCode() ?: 0)
        h = 31 * h + (artwork?.contentHashCode() ?: 0)
        return h
    }
}
