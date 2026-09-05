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

    /**
     * Temp-headroom multiplier for the free-space gate. While a job runs, the
     * published output is not the only thing on disk: a photo stages a full
     * source copy plus its encode in cache, a video transcode holds the first
     * pass AND (when the corrective pass engages) a second temp plus the audio
     * temp, and the result is then copied out of cache into MediaStore. The
     * measured worst case is roughly three times the final output alive at
     * once, so the gate asks for the output plus twice it of scratch room.
     *
     * SPACE-MODEL-FIX (X3): the old 1.15x slack counted only the published
     * bytes, so a big batch on a nearly-full disk started and died mid-way
     * with an IOException. Over-warning is safe here — the dialog always
     * offers "continue anyway".
     */
    private const val TEMP_HEADROOM_FACTOR = 2.0

    fun freeBytes(): Long =
        android.os.Environment.getDataDirectory().let {
            android.os.StatFs(it.path).availableBytes
        }

    /** Pure half of the gate (unit-testable without a filesystem). */
    fun requiredFreeBytes(neededBytes: Long): Long =
        if (neededBytes <= 0L) 0L
        else (neededBytes * (1.0 + TEMP_HEADROOM_FACTOR)).toLong()

    fun hasEnoughSpace(neededBytes: Long): Boolean =
        neededBytes <= 0 || freeBytes() > requiredFreeBytes(neededBytes)

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
