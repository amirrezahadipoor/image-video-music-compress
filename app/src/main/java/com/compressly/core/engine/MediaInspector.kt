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
            // Frame rate drives the encoder's rate controller; the old code
            // guessed 30 and mispriced every 60 fps phone clip.
            // There is no MediaMetadataRetriever key for the frame rate
            // (METADATA_KEY_CAPTURE_FRAMERATE is camera-only), so it is read off
            // the video track, in the same extractor pass that measures audio.
            val (frameRate, audioBitrate) = probeTracks(context, uri)
            return MediaInfo(
                mimeType = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                frameRate = frameRate,
                videoBitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                // METADATA_KEY_BITRATE is the whole-container rate, so the audio
                // track has to be measured separately before the video part can
                // be used as a ceiling.
                audioBitrate = audioBitrate,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                audioSampleRate = key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
                // Audio channel count: METADATA_KEY_NUM_CHANNELS (value=34) was added
                // in API 29 and its symbol is not defined in the API 26 SDK, so using
                // the named constant causes a compile error on minSdk 26.
                // We use the raw integer value (34) with a runtime SDK guard so the
                // compiler never sees the symbol on API 26-28. On API 26-28 we return 0
                // and let each engine read KEY_CHANNEL_COUNT from MediaExtractor instead.
                audioChannels = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    key(34 /* METADATA_KEY_NUM_CHANNELS */)?.toIntOrNull() ?: 0
                } else {
                    0
                },
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Reads the video frame rate and the audio bitrate straight off the tracks,
     * in a single extractor pass. Either value is 0 when its track is missing or
     * does not report it; every caller already falls back to a safe default.
     */
    private fun probeTracks(context: Context, uri: Uri): Pair<Int, Int> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            fun intAt(index: Int?, key: String): Int? {
                if (index == null || index < 0) return null
                val format = extractor.getTrackFormat(index)
                if (!format.containsKey(key)) return null
                return runCatching { format.getInteger(key) }.getOrNull()
            }

            val video = MediaUtil.findTrack(extractor, "video/")
            val audio = MediaUtil.findTrack(extractor, "audio/")
            val fps = (intAt(video, android.media.MediaFormat.KEY_FRAME_RATE) ?: 0).coerceIn(0, 240)
            val audioBps = (intAt(audio, android.media.MediaFormat.KEY_BIT_RATE) ?: 0).coerceAtLeast(0)
            fps to audioBps
        } catch (t: Throwable) {
            0 to 0
        } finally {
            runCatching { extractor.release() }
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
