package com.compressly.core.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.MediaType

/**
 * Scans a user-picked SAF folder (tree URI) for supported media files and
 * builds [InputItem]s grouped by type — the "pick a whole album" flow.
 *
 * Bounded on purpose: a gallery folder with thousands of shots must not turn
 * into a minute-long scan or an OOM. Depth is capped at [MAX_DEPTH] levels and
 * files at [MAX_FILES]; anything beyond is reported as truncated (the user is
 * told, nothing is dropped silently).
 */
object FolderMediaScanner {

    private const val MAX_FILES = 500
    private const val MAX_DEPTH = 3

    data class Snapshot(
        val photos: List<InputItem>,
        val videos: List<InputItem>,
        val audios: List<InputItem>,
        val truncated: Boolean
    ) {
        val total: Int get() = photos.size + videos.size + audios.size

        fun itemsOf(type: MediaType): List<InputItem> = when (type) {
            MediaType.PHOTO -> photos
            MediaType.VIDEO -> videos
            MediaType.AUDIO -> audios
        }
    }

    fun scan(context: Context, treeUri: Uri): Snapshot {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return Snapshot(emptyList(), emptyList(), emptyList(), false)
        val photos = ArrayList<InputItem>()
        val videos = ArrayList<InputItem>()
        val audios = ArrayList<InputItem>()
        var truncated = false
        var count = 0

        fun add(file: DocumentFile, type: MediaType, list: MutableList<InputItem>) {
            if (count++ >= MAX_FILES) {
                truncated = true
                return
            }
            val size = file.length()
            list += InputItem(
                itemId = System.nanoTime() + count.toLong(),
                uri = file.uri,
                displayName = file.name ?: file.uri.lastPathSegment ?: "file",
                sizeBytes = size,
                mediaType = type
            )
        }

        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > MAX_DEPTH || truncated) return
            dir.listFiles().forEach { file ->
                if (truncated) return@forEach
                if (file.isDirectory) {
                    walk(file, depth + 1)
                } else {
                    when (typeOf(file)) {
                        MediaType.PHOTO -> add(file, MediaType.PHOTO, photos)
                        MediaType.VIDEO -> add(file, MediaType.VIDEO, videos)
                        MediaType.AUDIO -> add(file, MediaType.AUDIO, audios)
                        null -> Unit
                    }
                }
            }
        }

        walk(root, 1)
        return Snapshot(photos, videos, audios, truncated)
    }

    private fun typeOf(file: DocumentFile): MediaType? {
        val name = (file.name ?: "").lowercase()
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return when (name.substring(dot + 1)) {
            "jpg", "jpeg", "png", "webp", "heic", "heif" -> MediaType.PHOTO
            "mp4", "mov", "mkv", "webm", "3gp", "3gpp", "m4v", "avi" -> MediaType.VIDEO
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "amr" -> MediaType.AUDIO
            else -> null
        }
    }
}
