package com.compressly

import com.compressly.core.util.Bidi
import org.junit.Assert.assertEquals
import org.junit.Test

/** The isolate helpers must be no-ops for Persian and stable for Latin. */
class BidiTest {

    @Test
    fun isolate_wrapsWithInvisibleCharacters() {
        assertEquals("\u2066IMG_2024.jpg\u2069", Bidi.isolate("IMG_2024.jpg"))
    }

    @Test
    fun isolate_blankStaysBlank() {
        assertEquals("", Bidi.isolate(""))
        assertEquals("   ", Bidi.isolate("   "))
    }

    @Test
    fun isolate_persianIsStillValidIsolate() {
        // Wrapping Persian text in LRI/PDI is visually a no-op; it only
        // guarantees no direction leaks.
        val wrapped = Bidi.isolate("فایل آزمایشی.jpg")
        assertEquals("\u2066فایل آزمایشی.jpg\u2069", wrapped)
    }

    @Test
    fun isolateLines_preservesLineBreaks() {
        assertEquals(
            "\u2066a.jpg\u2069\n\u2066b.jpg\u2069",
            Bidi.isolateLines("a.jpg\nb.jpg")
        )
        assertEquals("", Bidi.isolateLines(""))
    }
}
