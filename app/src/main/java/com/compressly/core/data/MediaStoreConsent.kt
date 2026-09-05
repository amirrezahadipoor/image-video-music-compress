package com.compressly.core.data

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.net.Uri
import android.provider.MediaStore

/**
 * "Replace original" needs more than a read grant.
 *
 * A media URI that came from the photo picker (or from another app) belongs to a
 * MediaStore row this app did not create, so on Android 10+ the system refuses to
 * let us modify or remove it: `openOutputStream(uri, "w")` returns null and
 * `contentResolver.delete` reports nothing. Every layer of the pipeline handled
 * that by carrying on quietly, which is why a batch of photos "finished" with all
 * of its originals intact — the single most-reported bug of this app.
 *
 * The fix has to happen where the user is still looking at the screen that asked
 * for it: ask MediaStore, once for the whole batch, for the right to replace
 * these specific files, and only then start the job. This object owns that
 * question; it never touches the job itself, so a denied dialog is a *smaller*
 * failure (originals kept, output published next to them) rather than a crash.
 */
object MediaStoreConsent {

    /** What has to be asked for a batch, and in which order. */
    data class Plan(
        /** Rows to overwrite in place: needs the edit grant. */
        val write: List<Uri>,
        /** Rows whose original has to be *removed* after a new row was published:
         *  the edit grant does not cover deletion, so this is a second dialog. */
        val delete: List<Uri>
    ) {
        val isEmpty: Boolean get() = write.isEmpty() && delete.isEmpty()
    }

    /**
     * Splits [uris] into what must be asked for. Empty on older releases, where no
     * such request exists — there the per-file fallback (publish a new row, keep
     * the original and say so) is the only possible behaviour, and the result
     * screen reports it.
     *
     * [inPlacePossible] is supplied by the caller because only the engine knows
     * whether a given row can be overwritten where it stands (same MIME family, or
     * a MediaStore row whose MIME and name can be retyped first). Anything it
     * answers false for becomes publish-a-new-row, and that needs the delete
     * grant — the pair used to be the gap that let a whole photo batch finish with
     * every original still in the gallery.
     */
    fun plan(context: Context, uris: List<Uri>, inPlacePossible: (Uri) -> Boolean): Plan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Plan(emptyList(), emptyList())
        val foreign = uris.filter { uri -> isMediaStoreRow(uri) && !isOwned(context, uri) }
        if (foreign.isEmpty()) return Plan(emptyList(), emptyList())
        val (overwritable, replaceable) = foreign.partition { inPlacePossible(it) }
        return Plan(write = overwritable, delete = replaceable.distinct())
    }

    /** The rows in [uris] this app may not write to on its own. */
    fun needsWriteConsent(context: Context, uris: List<Uri>): List<Uri> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) emptyList()
        else uris.filter { uri -> isMediaStoreRow(uri) && !isOwned(context, uri) }

    /**
     * One system dialog for the whole batch (the alternative is one dialog per
     * file, which is why the old per-file approach was never wired up). Returns
     * null when the release is too old, nothing needs asking, or MediaStore
     * refuses the request — a null here simply means "start without asking".
     */
    fun writeRequest(context: Context, uris: List<Uri>): PendingIntent? =
        batchRequest(context, uris, delete = false)

    /** Same shape as [writeRequest], for "remove the originals I could not remove". */
    fun deleteRequest(context: Context, uris: List<Uri>): PendingIntent? =
        batchRequest(context, uris, delete = true)

    private fun batchRequest(context: Context, uris: List<Uri>, delete: Boolean): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val rows = uris.filter { isMediaStoreRow(it) && !isOwned(context, it) }.distinct()
        if (rows.isEmpty()) return null
        return runCatching {
            if (delete) MediaStore.createDeleteRequest(context.contentResolver, rows)
            else MediaStore.createWriteRequest(context.contentResolver, rows)
        }.getOrNull()
    }

    /**
     * True for `content://media/.../<id>` URIs. Documents (SAF) and file URIs are
     * deliberately excluded: their access comes from the tree permission the user
     * granted when they picked a folder, and MediaStore has nothing to approve.
     */
    internal fun isMediaStoreRow(uri: Uri): Boolean =
        uri.scheme == "content" &&
            uri.authority?.contains("media") == true &&
            uri.pathSegments.lastOrNull()?.let { seg -> seg.all { it.isDigit() } } == true

    /**
     * Only the owning app can read OWNER_PACKAGE_NAME, so a null/foreign value
     * means exactly one thing: we cannot touch this row without asking.
     */
    private fun isOwned(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)
                ?.use { c -> c.moveToFirst() && !c.isNull(0) && c.getString(0) == context.packageName }
        }.getOrDefault(false) == true
    }
}
