package com.compressly.core.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore

/**
 * Offline storage summary used by the storage dashboard (B6).
 *
 * Two independent facts:
 *  - device storage (total / free) via StatFs — no permission required;
 *  - the largest media files currently on the device, read from MediaStore.
 *
 * MediaStore needs read permission; when it is not granted the file list is
 * simply empty and the screen shows an honest empty-state instead of a
 * permission crash. Nothing here touches the network.
 */
class StorageRepository(private val context: Context) {

    data class DeviceStorage(val totalBytes: Long, val freeBytes: Long) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
        val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    }

    data class StorageFileEntry(
        val name: String,
        val sizeBytes: Long,
        val mimeType: String?
    )

    /** Total and free space of the shared, user-facing storage. */
    fun deviceStorage(): DeviceStorage? = runCatching {
        // The shared external (user-visible) volume. getExternalStorageDirectory
        // is the volume the user thinks of as "the memory", and is permission-free
        // for StatFs. Fall back to internal data dir if it is unavailable.
        val path = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrNull() ?: context.filesDir.absolutePath
        val stat = StatFs(path)
        DeviceStorage(
            totalBytes = stat.totalBytes,
            freeBytes = stat.availableBytes
        )
    }.getOrNull()

    /**
     * The [topN] largest media files on the device (images, videos, audio),
     * biggest first. Best-effort: an unreadable volume or a missing read
     * permission yields an empty list, never a crash.
     */
    fun largestMedia(topN: Int): List<StorageFileEntry> = runCatching {
        val results = mutableListOf<StorageFileEntry>()
        for (uri in arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )) {
            // The projection and selection are safe (quoted literals only).
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val sort = "${MediaStore.MediaColumns.SIZE} DESC"
            try {
                context.contentResolver.query(
                    uri, projection,
                    "${MediaStore.MediaColumns.SIZE} > ?", arrayOf("0"),
                    sort
                )?.use { c ->
                    val nameI = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeI = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val mimeI = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    while (c.moveToNext()) {
                        val size = if (sizeI >= 0) c.getLong(sizeI) else 0L
                        if (size > 0) {
                            results += StorageFileEntry(
                                name = if (nameI >= 0) c.getString(nameI) ?: "" else "",
                                sizeBytes = size,
                                mimeType = if (mimeI >= 0) c.getString(mimeI) else null
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Permission not granted / volume unreadable — keep going.
            }
        }
        results.sortedByDescending { it.sizeBytes }.take(topN)
    }.getOrDefault(emptyList())
}
