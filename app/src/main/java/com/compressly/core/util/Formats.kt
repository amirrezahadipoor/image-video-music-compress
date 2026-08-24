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
        val raw = when {
            bytes < kb -> "${bytes} B"
            bytes < mb -> String.format(Locale.US, "%.1f KB", value / kb)
            bytes < gb -> String.format(Locale.US, "%.1f MB", value / mb)
            else -> String.format(Locale.US, "%.2f GB", value / gb)
        }
        return if (Locale.getDefault().language == "fa") toPersianDigits(raw) else raw
    }

    fun percent(reduction: Double): String {
        val raw = String.format(Locale.US, "%.0f%%", (reduction * 100).coerceIn(0.0, 100.0))
        return if (Locale.getDefault().language == "fa") toPersianDigits(raw) else raw
    }

    /** 0.0..1.0 fraction as a whole percent. */
    fun percentFraction(fraction: Float): String {
        val raw = "${(fraction * 100).toInt().coerceIn(0, 100)}%"
        return if (Locale.getDefault().language == "fa") toPersianDigits(raw) else raw
    }

    fun humanDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val raw = if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
        return if (Locale.getDefault().language == "fa") toPersianDigits(raw) else raw
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
        val raw = when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
        // Use Persian digits when the current locale is Persian.
        return if (Locale.getDefault().language == "fa") toPersianDigits(raw) else raw
    }

    /** Convert ASCII digits in a string to Persian (Eastern Arabic) digits. */
    private fun toPersianDigits(s: String): String {
        val pd = charArrayOf('\u06F0','\u06F1','\u06F2','\u06F3','\u06F4','\u06F5','\u06F6','\u06F7','\u06F8','\u06F9')
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in '0'..'9') sb.append(pd[c - '0']) else sb.append(c)
        }
        return sb.toString()
    }
}
