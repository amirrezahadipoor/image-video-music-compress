package com.compressly.core.util

import java.util.Locale

/** Human-readable formatting used everywhere in the UI. */
object Formats {

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val value = bytes.toDouble()
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes < kb -> "${bytes} B"
            bytes < mb -> String.format(Locale.US, "%.1f KB", value / kb)
            bytes < gb -> String.format(Locale.US, "%.1f MB", value / mb)
            else -> String.format(Locale.US, "%.2f GB", value / gb)
        }
    }

    fun percent(reduction: Double): String =
        String.format(Locale.US, "%.0f%%", (reduction * 100).coerceAtLeast(0.0))

    /** 0.0..1.0 fraction as a whole percent. */
    fun percentFraction(fraction: Float): String =
        "${(fraction * 100).toInt().coerceIn(0, 100)}%"

    fun humanDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Relative time such as "3h 12m" / "5m 40s" / "8s". */
    fun timeAgo(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return compactDuration(diff.coerceAtLeast(0))
    }

    /** "3h 12m" / "5m 40s" / "8s" style durations for progress ETA. */
    fun compactDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
