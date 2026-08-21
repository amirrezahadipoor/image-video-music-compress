package com.compressly

import com.compressly.core.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun humanSize_usesDecimalUnits() {
        assertEquals("0 B", Formats.humanSize(0))
        assertEquals("512 B", Formats.humanSize(512))
        assertEquals("1.0 KB", Formats.humanSize(1024))
        assertEquals("1.5 MB", Formats.humanSize(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", Formats.humanSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun humanSize_neverNegative() {
        assertEquals("0 B", Formats.humanSize(-5))
    }

    @Test
    fun percent_formats() {
        assertEquals("50%", Formats.percent(0.5))
        assertEquals("0%", Formats.percent(-0.2))
        assertEquals("100%", Formats.percent(1.5))
    }

    @Test
    fun humanDuration() {
        assertEquals("0:00", Formats.humanDuration(0))
        assertEquals("0:59", Formats.humanDuration(59_000))
        assertEquals("1:05", Formats.humanDuration(65_000))
        assertEquals("1:02:03", Formats.humanDuration(3_723_000))
    }

    @Test
    fun compactDuration() {
        assertEquals("0s", Formats.compactDuration(0))
        assertEquals("8s", Formats.compactDuration(8_000))
        assertEquals("1m 5s", Formats.compactDuration(65_000))
        assertEquals("2h 5m", Formats.compactDuration(7_500_000))
    }
}
