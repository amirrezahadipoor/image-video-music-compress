package com.compressly.ui.navigation

object Routes {
    const val ONBOARDING     = "onboarding"
    const val HOME           = "home"
    const val HISTORY        = "history"
    const val APP_SETTINGS   = "app_settings"
    const val PREMIUM         = "premium"
    const val SUPPORT         = "support"
    const val PRIVACY         = "privacy"
    const val SETTINGS_PATTERN = "settings/{type}"
    fun settings(type: String) = "settings/$type"
    const val PROGRESS_PATTERN = "progress/{jobId}"
    fun progress(jobId: Long) = "progress/$jobId"
    const val RESULT_PATTERN   = "result/{entryId}"
    fun result(entryId: Long) = "result/$entryId"
}
