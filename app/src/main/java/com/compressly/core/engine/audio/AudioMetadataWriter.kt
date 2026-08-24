package com.compressly.core.engine.audio

import com.compressly.core.engine.model.AudioTags
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * Writes ID3v2 / MP4 metadata (title, artist, album, cover art) into an
 * already-encoded output file using jaudiotagger. Fully offline.
 *
 * Metadata writing is best-effort: the encoded audio is always delivered
 * even if tagging fails (the error is swallowed at the call site).
 */
object AudioMetadataWriter {

    fun write(file: File, tags: AudioTags) {
        if (tags.title == null && tags.artist == null && tags.album == null &&
            tags.albumArtist == null && tags.genre == null && tags.year == null &&
            tags.track == null && tags.comment == null && tags.artwork == null
        ) {
            return
        }
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.getTagOrCreateAndSetDefault()
        tags.title?.let { tag.setField(FieldKey.TITLE, it) }
        tags.artist?.let { tag.setField(FieldKey.ARTIST, it) }
        tags.album?.let { tag.setField(FieldKey.ALBUM, it) }
        tags.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        tags.genre?.let { tag.setField(FieldKey.GENRE, it) }
        tags.year?.let { tag.setField(FieldKey.YEAR, it) }
        tags.track?.let { tag.setField(FieldKey.TRACK, it) }
        tags.comment?.let { tag.setField(FieldKey.COMMENT, it) }
        tags.artwork?.let { bytes ->
            runCatching { tag.deleteArtworkField() }
            val artwork = ArtworkFactory.getNew().apply {
                setBinaryData(bytes)
                // Detect MIME from magic bytes instead of hardcoding JPEG.
                // PNG: 0x89504E47, JPEG: 0xFFD8FF
                setMimeType(
                    when {
                        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                        else -> "image/jpeg"
                    }
                )
            }
            tag.setField(artwork)
        }
        audioFile.commit()
    }
}
