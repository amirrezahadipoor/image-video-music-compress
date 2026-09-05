package com.compressly.core.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

object Mime {
    fun photoExtension(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

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

    /**
     * The dot-prefixed file extension a published file must carry for this MIME
     * type, so the name always matches the container inside it. Single source of
     * truth: OutputStore used to keep its own duplicate copy of this table while
     * these helpers sat unused.
     */
    fun extensionFor(mimeType: String?): String = when {
        mimeType == null -> ""
        mimeType.startsWith("image/") -> "." + photoExtension(mimeType)
        mimeType.startsWith("video/") -> "." + videoExtension(mimeType)
        mimeType.startsWith("audio/") -> "." + audioExtension(mimeType)
        else -> ""
    }
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

    /**
     * False once the row is gone (deleted here, or removed in another app).
     * A failed query counts as PRESENT: an unknown answer must never be what
     * convinces the UI that a user's file has been deleted.
     */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() }
    }.getOrDefault(true) == true

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
