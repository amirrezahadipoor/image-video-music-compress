package com.compressly.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.compressly.core.engine.model.AudioTags
import com.compressly.core.engine.model.MediaInfo

/**
 * Reads metadata from any local media URI using MediaMetadataRetriever.
 * Fully offline; the retriever is always released.
 */
object MediaInspector {

    fun inspect(context: Context, uri: Uri): MediaInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            fun key(k: Int): String? = runCatching { mmr.extractMetadata(k) }.getOrNull()

            val hasVideo = key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return MediaInfo(
                mimeType = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                videoBitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                audioBitrate = 0,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                audioSampleRate = key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
                audioChannels = key(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull() ?: 0,
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Reads ID3/MP4 tags plus embedded artwork (for audio metadata preservation). */
    fun readTags(context: Context, uri: Uri): AudioTags {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            fun key(k: Int): String? = runCatching { mmr.extractMetadata(k) }.getOrNull()
            // embeddedPicture returns the raw image bytes (JPEG/PNG).
            val artwork: ByteArray? = runCatching { mmr.embeddedPicture }.getOrNull()
            return AudioTags(
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = key(MediaMetadataRetriever.METADATA_KEY_YEAR),
                track = key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                artwork = artwork
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** A video frame as a thumbnail (Coil also provides this via its own decoder). */
    fun videoFrame(context: Context, uri: Uri, timeUs: Long = 1_000_000): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
