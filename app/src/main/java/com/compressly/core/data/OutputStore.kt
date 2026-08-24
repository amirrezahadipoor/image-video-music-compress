package com.compressly.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.compressly.core.engine.model.MediaType
import com.compressly.core.util.Storage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes compressed temp files into the device's media library via
 * MediaStore (scoped-storage compliant on API 29+; uses the legacy
 * WRITE_EXTERNAL_STORAGE path on Android 8.0-8.1).
 *
 * Rows are inserted with IS_PENDING on API 29+ so partially-written files
 * are never visible to the user; on failure/cancel the row is deleted.
 */
object OutputStore {

    fun createOutputUri(
        context: Context,
        mediaType: MediaType,
        displayName: String,
        mimeType: String,
        uniqueName: String
    ): Uri {
        // getContentUri(volume) is API 29+; older devices use the legacy URI.
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            when (mediaType) {
                MediaType.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaType.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            when (mediaType) {
                MediaType.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.TITLE, baseName(displayName))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(mediaType))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        return uri
    }

    /**
     * Copies [tempFile] into a new MediaStore row and returns the public URI.
     * The temp file is deleted afterwards.
     */
    fun publishTempFile(
        context: Context,
        mediaType: MediaType,
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Uri {
        val uniqueName = uniqueNameFor(displayName, mimeType)
        val uri = createOutputUri(context, mediaType, displayName, mimeType, uniqueName)
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Cannot open output stream")
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }
            Storage.deleteQuietly(tempFile)
            return uri
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    /** Removes a previously published output (cancel/failure cleanup). */
    fun delete(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun relativePathFor(mediaType: MediaType): String = when (mediaType) {
        MediaType.PHOTO -> "Pictures/Compressly"
        MediaType.VIDEO -> "Movies/Compressly"
        MediaType.AUDIO -> "Music/Compressly"
    }

    private fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    /** Thread-safe date format cache — one SimpleDateFormat per locale. */
    private val dateFormatCache = ConcurrentHashMap<String, SimpleDateFormat>()

    fun uniqueNameFor(displayName: String, mimeType: String): String {
        val dot = displayName.lastIndexOf('.')
        val name = if (dot > 0) displayName.substring(0, dot) else displayName
        
        // Correct the extension based on the actual output mimeType
        val ext = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "audio/mp4", "audio/m4a" -> ".m4a"
            "audio/mpeg" -> ".mp3"
            else -> if (dot > 0) displayName.substring(dot) else ""
        }
        
        val fmt = dateFormatCache.getOrPut("stamp") {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
        val stamp: String = synchronized(fmt) { fmt.format(Date()) }
        return "${name}_compressed_$stamp$ext"
    }
}
