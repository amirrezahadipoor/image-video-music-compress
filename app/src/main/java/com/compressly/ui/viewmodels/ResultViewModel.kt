package com.compressly.ui.viewmodels

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.siliksama.hajmino.R
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.MediaType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Drives the Result screen for one history entry. */
class ResultViewModel(container: AppContainer, private val entryId: Long) : ViewModel() {

    private val repository = container.historyRepository

    val entry: StateFlow<HistoryEntry?> = repository.observeEntry(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All entries of the same job (for batch summaries). */
    suspend fun loadSiblings(entry: HistoryEntry): List<HistoryEntry> = repository.getByJob(entry.jobId)

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

    fun open(context: Context, entry: HistoryEntry) {
        val uri = entry.outputUri?.let { Uri.parse(it) } ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(entry.mediaType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No viewer installed; fall back to the share sheet.
            share(context, entry)
        }
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
