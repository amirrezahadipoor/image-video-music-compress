package com.compressly.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.OutputLocation
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

    /**
     * Optional custom output folder (SAF tree URI, see AppSettingsScreen).
     * null = the default MediaStore folders. Set by CompresslyApp from the
     * persisted setting, and immediately by the settings view model.
     */
    @Volatile
    private var customTreeUri: String? = null

    fun setCustomTreeUri(uri: String?) {
        customTreeUri = uri
    }

    fun customTreeUri(): String? = customTreeUri

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
     * REPLACE-IN-PLACE: overwrites the SOURCE document with the compressed
     * bytes, keeping the same URI/path — the true meaning of "replace". This
     * is the only replace that cannot create a duplicate: a new MediaStore row
     * is never inserted, so there is exactly one file left where the source
     * was. Returns the source URI on success, or null when the source can't be
     * written (e.g. a read-only picker grant) — the caller then falls back.
     */
    fun replaceInPlace(
        context: Context,
        sourceUri: Uri,
        tempFile: File
    ): Uri? {
        return try {
            val out = runCatching { context.contentResolver.openOutputStream(sourceUri, "w") }.getOrNull()
                ?: return null
            out.use { os -> tempFile.inputStream().use { it.copyTo(os, 256 * 1024) } }
            Storage.deleteQuietly(tempFile)
            sourceUri
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Copies [tempFile] into a new MediaStore row and returns the public URI.
     * The temp file is deleted afterwards. Honors a custom output folder and the
     * requested [location]; any failure falls back to the default MediaStore
     * path so a folder preference can never break a job.
     */
    fun publishTempFile(
        context: Context,
        mediaType: MediaType,
        tempFile: File,
        displayName: String,
        mimeType: String,
        location: OutputLocation = OutputLocation.DEFAULT,
        sourceUri: Uri? = null,
        customTreeUri: String? = null
    ): Uri {
        when (location) {
            // Replace in place: write the result into the SAME folder as the
            // source (its RELATIVE_PATH), so it truly replaces the original.
            OutputLocation.SAME_AS_SOURCE -> {
                val relative = sourceUri?.let { relativePathOf(context, it) }
                if (relative != null) {
                    runCatching {
                        publishToRelativePath(context, mediaType, tempFile, displayName, mimeType, relative)
                    }.getOrNull()?.let { return it }
                    // If that failed, fall through to the default rather than fail.
                }
            }
            OutputLocation.CUSTOM -> {
                customTreeUri?.let { tree ->
                    runCatching { publishToTree(context, tree, mediaType, tempFile, displayName, mimeType) }
                        .getOrNull()?.let { return it }
                }
            }
            OutputLocation.DEFAULT -> Unit
        }
        // Default output folder: write into the SAF tree under Hajmino/<type>.
        // Any failure falls back to the default MediaStore path below — a
        // folder preference must never be able to break a job.
        customTreeUri?.let { tree ->
            runCatching { publishToTree(context, tree, mediaType, tempFile, displayName, mimeType) }
                .getOrNull()?.let { return it }
        }
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

    /** The MediaStore RELATIVE_PATH of a content source, or null when unknown/not MediaStore. */
    private fun relativePathOf(context: Context, sourceUri: Uri): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            context.contentResolver.query(
                sourceUri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Publishes into a specific MediaStore folder (relative path). */
    private fun publishToRelativePath(
        context: Context,
        mediaType: MediaType,
        tempFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Uri {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            when (mediaType) {
                MediaType.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaType.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            throw IOException("RELATIVE_PATH requires API 29+")
        }
        val uniqueName = uniqueNameFor(displayName, mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.TITLE, baseName(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Cannot open output stream")
            val values2 = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values2, null, null)
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
        // SAF-6 FIX: a document created inside a custom tree is exposed to the
        // app as a content:// document URI. `DocumentFile.fromSingleUri` often
        // cannot resolve it and `contentResolver.delete` does not work for
        // tree-backed documents either — so an orphaned half-written file
        // would stay in the user's folder after a cancel/failure. Resolve the
        // tree URI we hold and look the document up by name as a last resort.
        val deleted = runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
            .getOrDefault(false) ?: false
        if (!deleted) {
            val tree = customTreeUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it)) }.getOrNull() }
            if (tree != null) {
                val found = runCatching {
                    val name = nameOf(uri)
                    // Walk the same <tree>/Hajmino/<Photos|Videos|Audio>/ layout a
                    // document may live in: search every subfolder for the name.
                    val hajmino = tree.findFile("Hajmino") ?: return@runCatching false
                    hajmino.listFiles()
                        .firstNotNullOfOrNull { sub -> sub.listFiles().firstOrNull { it.name == name } }
                        ?.delete() ?: false
                }.getOrDefault(false) ?: false
                if (found) return
            }
        }
        if (!deleted) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun nameOf(uri: Uri): String {
        val seg = uri.lastPathSegment ?: return ""
        val idx = seg.lastIndexOf('/')
        return if (idx >= 0) seg.substring(idx + 1) else seg
    }

    /** Writes [tempFile] into the custom tree: <tree>/Hajmino/<Photos|Videos|Audio>. */
    private fun publishToTree(
        context: Context,
        treeUri: String,
        mediaType: MediaType,
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Uri {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IOException("Invalid output folder")
        val root = tree.findFile("Hajmino") ?: tree.createDirectory("Hajmino")
            ?: throw IOException("Cannot create folder")
        val subName = when (mediaType) {
            MediaType.PHOTO -> "Photos"
            MediaType.VIDEO -> "Videos"
            MediaType.AUDIO -> "Audio"
        }
        val sub = root.findFile(subName) ?: root.createDirectory(subName)
            ?: throw IOException("Cannot create subfolder")
        val uniqueName = uniqueNameFor(displayName, mimeType)
        val doc = sub.createFile(mimeType, uniqueName)
            ?: throw IOException("Cannot create file")
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            tempFile.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
        } ?: throw IOException("Cannot open output stream")
        Storage.deleteQuietly(tempFile)
        return doc.uri
    }

    private fun relativePathFor(mediaType: MediaType): String = when (mediaType) {
        MediaType.PHOTO -> "Pictures/Hajmino"
        MediaType.VIDEO -> "Movies/Hajmino"
        MediaType.AUDIO -> "Music/Hajmino"
    }

    private fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    /** Thread-safe date format cache — one SimpleDateFormat per locale. */
    private val dateFormatCache = ConcurrentHashMap<String, SimpleDateFormat>()

    private val nameSequence = java.util.concurrent.atomic.AtomicInteger(0)

    fun uniqueNameFor(displayName: String, mimeType: String): String {
        val dot = displayName.lastIndexOf('.')
        val name = if (dot > 0) displayName.substring(0, dot) else displayName
        
        // Correct the extension based on the actual output mimeType
        val ext = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a"
            "audio/mpeg" -> ".mp3"
            "audio/flac" -> ".flac"
            "audio/wav", "audio/x-wav", "audio/vnd.wave" -> ".wav"
            "audio/ogg", "audio/opus" -> ".ogg"
            "audio/aac", "audio/adts" -> ".aac"
            "audio/amr", "audio/amr-wb" -> ".amr"
            else -> if (dot > 0) displayName.substring(dot) else ""
        }
        
        val fmt = dateFormatCache.getOrPut("stamp") {
            // Milliseconds, not seconds. A batch of photos finishes several
            // within the same second, and every one of them got the same
            // DISPLAY_NAME - MediaStore then had to rename them out of the way,
            // so the gallery ended up full of "name (1).jpg", "name (2).jpg".
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        }
        val stamp: String = synchronized(fmt) { fmt.format(Date()) }
        // Two files can still land in the same millisecond on a fast batch, so a
        // monotonic sequence guarantees uniqueness rather than hoping.
        val seq = nameSequence.incrementAndGet()
        return "${name}_compressed_${stamp}_$seq$ext"
    }
}
