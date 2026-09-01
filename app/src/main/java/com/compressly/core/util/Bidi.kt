package com.compressly.core.util

/**
 * Unicode isolate helpers for mixed-direction text.
 *
 * RTL-BIDI-FIX: a Latin file name ("IMG_2024.jpg") inside a Persian sentence
 * ("فشرده شد: IMG_2024.jpg") reorders the words on screen — the trailing
 * Latin token lands on the wrong side of the line, or the punctuation jumps.
 * The old punctuation-trick (appending a LRM/RLM character) fails inside
 * Compose spans and on both sides of a long Latin name. The correct fix is
 * real isolates: LRI (U+2066)…PDI (U+2069), which are invisible, valid at any
 * position and never leak direction outside the segment. They are also a
 * no-op for pure-Persian text, so they can be applied unconditionally.
 */
object Bidi {

    /** Left-to-right isolate + pop direction isolate, around [text]. */
    fun isolate(text: String): String {
        if (text.isBlank()) return text
        return "\u2066$text\u2069"
    }

    /** Isolate every non-empty line of a multi-line name (e.g. tag lines). */
    fun isolateLines(text: String): String =
        text.split('\n').joinToString("\n") { isolate(it) }
}
