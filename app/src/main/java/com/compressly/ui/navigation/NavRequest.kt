package com.compressly.ui.navigation

import com.compressly.core.engine.model.MediaType

/** Navigation intents produced outside the composable tree (notifications). */
sealed class NavRequest {
    data class OpenJob(val jobId: Long) : NavRequest()
    data class OpenEntry(val entryId: Long) : NavRequest()

    /** Open the compression-settings screen for the given media type (share-target). */
    data class OpenSettings(val mediaType: MediaType) : NavRequest()

    /**
     * Open the history list. Fallback for a notification tap whose job is no
     * longer in memory and left no result row to show.
     */
    data object OpenHistory : NavRequest()
}
