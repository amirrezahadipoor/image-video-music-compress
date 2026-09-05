package com.compressly.ui.viewmodels

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.siliksama.hajmino.R
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.data.MediaStoreConsent
import com.compressly.core.data.OutputStore
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Drives the Result screen for one history entry. */
class ResultViewModel(container: AppContainer, private val entryId: Long) : ViewModel() {

    private val repository = container.historyRepository

    val entry: StateFlow<HistoryEntry?> = repository.observeEntry(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every finished row of the same job, oldest first.
     *
     * JOB-SCOPE-FIX: this used to be the raw `getByJob` list, so (a) a row still
     * RUNNING (the user opened the result of item 1 while item 4 is compressing)
     * counted as 0 bytes and inflated the saving percentage, and (b) a retry of
     * the same file inside the job was summed twice. A failed row is kept — not
     * for the totals, which filter on DONE, but so the header can still say
     * "3 of 4 succeeded" instead of pretending nothing went wrong.
     */
    suspend fun loadSiblings(entry: HistoryEntry): List<HistoryEntry> {
        val rows = repository.getByJob(entry.jobId)
            .filter { it.status != HistoryEntry.STATUS_RUNNING }
            .sortedBy { it.id }
            .distinctBy { it.status to (it.outputUri ?: it.inputUri) }
        return if (rows.any { it.id == entry.id } || rows.isEmpty()) rows else rows + entry
    }

    fun share(context: Context, entry: HistoryEntry) {
        val uri = entry.outputUri?.let { Uri.parse(it) } ?: return
        val mime = mimeFor(entry.mediaType)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // createChooser can strip URI grant flags on Android 10+;
        // re-add them to the chooser intent so the receiving app can read.
        val chooser = Intent.createChooser(intent, context.getString(R.string.result_share_sheet_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(chooser) }
    }

    /**
     * BATCH-SHARE: shares one file per successfully compressed result of the same
     * job, so a whole folder/batch is shared with other apps instead of only the
     * single file the current screen happens to show.
     *
     * SHARE-SCOPE-FIX: the uris come from the job's own finished rows and are
     * de-duplicated, and the stream is carried in ClipData with a wildcard mime
     * type. Putting N uris in EXTRA_STREAM with a video-only type used to hand
     * the receiver the FIRST one only — and when the job's rows came from a
     * stale jobId, that "batch" was another folder's files entirely.
     */
    fun shareAll(context: Context, siblings: List<HistoryEntry>) {
        val uris = siblings
            .filter { it.status == HistoryEntry.STATUS_DONE && it.outputUri != null }
            .mapNotNull { it.outputUri?.let { u -> Uri.parse(u) } }
            .distinct()
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            share(context, siblings.first { it.status == HistoryEntry.STATUS_DONE })
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            // Mixed media (photos and videos in one job) must not be typed as
            // video/*: receivers filter the type first and silently drop images.
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            val clip = ClipData.newRawUri(context.getString(R.string.app_name), uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.result_share_sheet_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = intent.clipData
        }
        runCatching { context.startActivity(chooser) }
    }

    fun open(context: Context, entry: HistoryEntry) {
        val uri = entry.outputUri?.let { Uri.parse(it) } ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(entry.mediaType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!launch(context, intent)) {
            // No viewer installed; fall back to the share sheet.
            share(context, entry)
        }
    }

    /**
     * Shows the folder the result lives in, which is what "open the file" means
     * on a phone: the gallery, the player, or the file manager at that path.
     *
     * Three honest rungs, best available first — a folder is only a *document*
     * when it came from a SAF tree this app holds a grant for, and MediaStore has
     * no "show me this folder" affordance for third-party apps at all. The last
     * rung (open the file) is still better than the nothing this screen used to
     * offer after a folder job.
     */
    fun openFolder(context: Context, entry: HistoryEntry) {
        val raw = entry.outputUri ?: entry.inputUri
        val uri = raw?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return

        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            val parent = runCatching {
                val docId = DocumentsContract.getDocumentId(uri)
                val parentId = docId.substringBeforeLast('/', "").ifBlank {
                    DocumentsContract.getTreeDocumentId(uri)
                }
                DocumentsContract.buildTreeDocumentUri(uri.authority, parentId)
            }.getOrNull()
            if (parent != null && launch(
                    context,
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(parent, DocumentsContract.Document.MIME_TYPE_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            ) return
        }

        val collection = collectionOf(entry.mediaType)
        if (collection != null && launch(
                context,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(collection, dirTypeOf(entry.mediaType))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        ) return

        open(context, entry)
    }

    /**
     * The delete grant request that fixes "the original is still there" after the
     * user skipped or denied the up-front one. Null means nothing to ask: every
     * row is either ours already, or the release is too old to ask at all.
     */
    fun requestDeleteGrant(context: Context, entries: List<HistoryEntry>): PendingIntent? =
        MediaStoreConsent.deleteRequest(context, removableOriginals(entries).map { Uri.parse(it.inputUri) })

    /** Rows whose original still exists, was not overwritten in place, and can be removed. */
    fun removableOriginals(entries: List<HistoryEntry>): List<HistoryEntry> =
        entries.filter {
            it.status == HistoryEntry.STATUS_DONE &&
                it.originalRetained &&
                !it.outputUri.isNullOrBlank() &&
                it.outputUri != it.inputUri
        }

    /**
     * Removes the originals of [entries] — the action behind the result screen's
     * warning. Only ever called for rows the caller has verified have a separate
     * output, so the compressed file can never be deleted by this path.
     */
    suspend fun deleteOriginals(context: Context, entries: List<HistoryEntry>): Int {
        val targets = removableOriginals(entries)
        if (targets.isEmpty()) return 0
        val removed = withContext(Dispatchers.IO) {
            targets.filter { OutputStore.delete(context, Uri.parse(it.inputUri)) }
        }
        // A row that is really gone must not keep shouting "original retained".
        removed.forEach { e -> runCatching { repository.update(e.copy(error = null)) } }
        return removed.size
    }

    /**
     * Removes the "original retained" warning from rows whose file really is
     * gone — run after the user approves the system delete dialog, which does
     * the deleting itself, so the app must not claim a failure it did not have.
     */
    suspend fun refreshRetainedMarkers(context: Context, entries: List<HistoryEntry>) {
        withContext(Dispatchers.IO) {
            entries.filter { it.originalRetained }.forEach { e ->
                val uri = runCatching { Uri.parse(e.inputUri) }.getOrNull() ?: return@forEach
                if (!com.compressly.core.util.Uris.exists(context, uri)) {
                    runCatching { repository.update(e.copy(error = null)) }
                }
            }
        }
    }

    private fun collectionOf(mediaType: String): Uri? = when (MediaType.fromName(mediaType)) {
        MediaType.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun dirTypeOf(mediaType: String): String = when (MediaType.fromName(mediaType)) {
        MediaType.PHOTO -> "vnd.android.cursor.dir/image"
        MediaType.VIDEO -> "vnd.android.cursor.dir/video"
        MediaType.AUDIO -> "vnd.android.cursor.dir/audio"
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // A grant we no longer hold (folder unmounted, tree revoked) must never
        // crash the screen that is only trying to show a file.
        false
    }

    private fun mimeFor(mediaType: String): String = when (MediaType.fromName(mediaType)) {
        MediaType.PHOTO -> "image/*"
        MediaType.VIDEO -> "video/*"
        MediaType.AUDIO -> "audio/*"
    }

    companion object {
        fun factory(app: CompresslyApp, entryId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ResultViewModel(app.container, entryId) as T
        }
    }
}
