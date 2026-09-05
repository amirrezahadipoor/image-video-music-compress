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
     *
     * REPLACE-SAFE-FIX: opening the stream in "w" truncates the user's ONLY
     * copy of the file before a single byte of the replacement is written, so a
     * failure halfway through (volume full, I/O error, process death) used to
     * leave a 0-byte original *and* report success. Two guards now sit around
     * the write: free space for both copies is required up front (the common
     * cause of a mid-write failure), and the source is read back afterwards —
     * an empty file there is reported as a failure instead of being swallowed,
     * so the user learns the replacement went wrong instead of finding a 0-byte
     * photo in the gallery days later. A merely different length is only logged,
     * because MediaStore caches the row size and can lag behind the provider.
     */
    fun replaceInPlace(
        context: Context,
        sourceUri: Uri,
        tempFile: File
    ): Uri? {
        val expected = tempFile.length()
        if (expected <= 0L) return null
        // Room for the copy we are about to write. Without this the write dies
        // mid-file on a full volume, after the truncate already happened.
        if (!com.compressly.core.util.Storage.hasEnoughSpace(expected * 2)) return null
        var out: java.io.OutputStream? = null
        // Try the standard truncate mode first; some SAF document providers
        // only accept the explicit write modes. A source that can't be written
        // at all (read-only picker grant) falls through to null and the caller
        // publishes a new copy + deletes the original instead.
        for (mode in WRITE_MODES) {
            out = runCatching { context.contentResolver.openOutputStream(sourceUri, mode) }.getOrNull()
            if (out != null) break
        }
        if (out == null) return null
        try {
            out.use { os -> tempFile.inputStream().use { it.copyTo(os, 512 * 1024) } }
        } catch (t: Throwable) {
            // The source is already truncated here — surface it, never hide it.
            throw IOException("replace-in-place write failed after truncate", t)
        }
        // Verify what actually landed. A 0-length source after the write means
        // the user is holding an empty file — that must never be reported as a
        // successful replacement. A different-but-nonzero length is only logged:
        // MediaStore caches SIZE and can lag a moment behind the provider, so
        // failing on that would turn good jobs into false errors on real devices.
        val written = com.compressly.core.util.Uris.sizeOf(context, sourceUri)
        if (written == 0L) {
            throw IOException("replace-in-place left an empty file behind (expected $expected bytes)")
        }
        if (written > 0 && written != expected) {
            android.util.Log.w(
                "OutputStore",
                "replace-in-place size differs (reported $written, wrote $expected) — provider cache lag?"
            )
        }
        Storage.deleteQuietly(tempFile)
        return sourceUri
    }

    private val WRITE_MODES = arrayOf("w", "wt", "rwt")

    /**
     * Where the file actually landed compared with what was asked for.
     * [usedRequestedFolder] is false only when a specific folder was requested
     * and publishing there failed, so the result went to the default MediaStore
     * folder instead — a case that used to be completely invisible to the user.
     */
    data class Publication(val uri: Uri, val usedRequestedFolder: Boolean)

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
    ): Uri = publishTempFileDetailed(
        context, mediaType, tempFile, displayName, mimeType, location, sourceUri, customTreeUri
    ).uri

    /** See [publishTempFile]; this variant also reports whether the folder was honoured. */
    fun publishTempFileDetailed(
        context: Context,
        mediaType: MediaType,
        tempFile: File,
        displayName: String,
        mimeType: String,
        location: OutputLocation = OutputLocation.DEFAULT,
        sourceUri: Uri? = null,
        customTreeUri: String? = null
    ): Publication {
        val wantsSpecificFolder = location != OutputLocation.DEFAULT || customTreeUri != null
        when (location) {
            // Replace in place: write the result into the SAME folder as the
            // source (its RELATIVE_PATH), so it truly replaces the original.
            OutputLocation.SAME_AS_SOURCE -> {
                val relative = sourceUri?.let { relativePathOf(context, it) }
                if (relative != null) {
                    runCatching {
                        publishToRelativePath(context, mediaType, tempFile, displayName, mimeType, relative)
                    }.getOrNull()?.let { return Publication(it, true) }
                    // If that failed, fall through to the default rather than fail.
                }
            }
            OutputLocation.CUSTOM -> {
                customTreeUri?.let { tree ->
                    runCatching { publishToTree(context, tree, mediaType, tempFile, displayName, mimeType) }
                        .getOrNull()?.let { return Publication(it, true) }
                }
            }
            OutputLocation.DEFAULT -> Unit
        }
        // Default output folder: write into the SAF tree under Hajmino/<type>.
        // Any failure falls back to the default MediaStore path below — a
        // folder preference must never be able to break a job.
        customTreeUri?.let { tree ->
            runCatching { publishToTree(context, tree, mediaType, tempFile, displayName, mimeType) }
                .getOrNull()?.let { return Publication(it, true) }
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
            return Publication(uri, usedRequestedFolder = !wantsSpecificFolder)
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

    /**
     * Removes a previously published output (cancel/failure cleanup) or, when
     * the user asked to replace the original, the SOURCE file itself.
     *
     * DELETE-TRUTH-FIX: this used to return Unit, so "replace original" could
     * report a clean success while the original was still in the gallery — which
     * is precisely how users ended up with duplicate photos they never asked
     * for. It now reports whether the row is really gone; the caller marks the
     * history entry when it is not, and the result screen offers the fix.
     */
    fun delete(context: Context, uri: Uri?, jobTreeUri: String? = null): Boolean {
        if (uri == null) return false
        // SAF-6 FIX: a document created inside a custom tree is exposed to the
        // app as a content:// document URI. `DocumentFile.fromSingleUri` often
        // cannot resolve it and `contentResolver.delete` does not work for
        // tree-backed documents either — so an orphaned half-written file
        // would stay in the user's folder after a cancel/failure. Resolve the
        // tree URI we hold and look the document up by name as a last resort.
        val deleted = runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
            .getOrDefault(false) ?: false
        if (deleted) return true
        val tree = treeFor(context, jobTreeUri)
        if (tree != null) {
            val found = runCatching {
                val name = nameOf(uri)
                // Walk the same <tree>/Hajmino/<type>/ layout a document may
                // live in: search every subfolder for the name.
                val hajmino = tree.findFile("Hajmino") ?: return@runCatching false
                hajmino.listFiles()
                    .firstNotNullOfOrNull { sub -> sub.listFiles().firstOrNull { it.name == name } }
                    ?.delete() ?: false
            }.getOrDefault(false) ?: false
            if (found) return true
        }
        // Last resort: the bare MediaStore delete. On Android 10-11 a row this
        // app does not own answers with RecoverableSecurityException (or 0
        // rows) — that is not a silent success any more, it is a `false` the UI
        // must act on.
        val removed = runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (!removed) {
            android.util.Log.w("OutputStore", "could not remove $uri (no write/delete grant)")
        }
        return removed
    }

    /**
     * The SAF tree a deletion should search. [jobTreeUri] is the folder THIS job
     * publishes into; it takes precedence over the app-wide preference, which
     * used to be the only tree consulted and therefore never matched a per-job
     * folder (leaving orphans behind).
     */
    private fun treeFor(context: Context, jobTreeUri: String?): DocumentFile? {
        val raw = jobTreeUri ?: customTreeUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(raw)) }.getOrNull()
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
        
        // Correct the extension based on the actual output MIME type. Mime is the
        // single source of truth for the whole app now (this used to be a
        // duplicate table living next to identical, unused helpers).
        val ext = com.compressly.core.util.Mime.extensionFor(mimeType)
            .ifBlank { if (dot > 0) displayName.substring(dot) else "" }
        
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
