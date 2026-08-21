package com.compressly.core.util

import android.content.Context

/**
 * Last line of defense against crashes: records that a crash happened so the
 * next launch can tell the user politely ("the app restarted after an error"),
 * instead of them staring at a dead app with no explanation.
 */
object CrashGuard {

    private const val PREFS = "crash_prefs"
    private const val KEY_CRASHED = "last_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_CRASHED, System.currentTimeMillis()).apply()
            }
            // Let the system finish the crash flow (dialog + process death).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns true once if a crash was recorded since the last launch. */
    fun consumeCrash(context: Context): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val crashed = prefs.contains(KEY_CRASHED)
            prefs.edit().remove(KEY_CRASHED).apply()
            crashed
        }.getOrDefault(false)
    }
}
