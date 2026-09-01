package com.compressly.core.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

object Mime {
    fun forPhotoFormat(format: String): String = when (format.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

    fun photoExtension(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    fun isPhoto(mime: String?): Boolean =
        mime?.startsWith("image/") == true && mime != "image/gif"

    /** File extension for a video container, so a passthrough copy keeps its type. */
    fun videoExtension(mime: String?): String = when (mime?.lowercase()) {
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }

    /** File extension for an audio container, so output keeps the real
     *  container instead of being stamped .mp3 no matter what it is. */
    fun audioExtension(mime: String?): String = when (mime?.lowercase()) {
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/aac", "audio/adts" -> "aac"
        "audio/amr", "audio/amr-wb" -> "amr"
        else -> "mp3"
    }

    fun isVideo(mime: String?): Boolean = mime?.startsWith("video/") == true

    fun isAudio(mime: String?): Boolean = mime?.startsWith("audio/") == true
}

/** Local storage helpers. Everything stays on-device and offline. */
object Storage {

    fun freeBytes(): Long =
        android.os.Environment.getDataDirectory().let {
            android.os.StatFs(it.path).availableBytes
        }

    fun hasEnoughSpace(neededBytes: Long): Boolean =
        neededBytes <= 0 || freeBytes() > neededBytes * 1.15

    fun deleteQuietly(vararg files: File?) {
        for (f in files) {
            runCatching { f?.delete() }
        }
    }
}

object Uris {
    fun displayNameOf(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }
}
