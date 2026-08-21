package com.compressly.ui.navigation

/** Navigation intents produced outside the composable tree (notifications). */
sealed class NavRequest {
    data class OpenJob(val jobId: Long) : NavRequest()
    data class OpenEntry(val entryId: Long) : NavRequest()
}
