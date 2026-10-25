package com.compressly

import com.compressly.core.util.Uris
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1 (test gap) — the "is this file still there?" rule (audit item A4).
 *
 * The rule is deliberately one-sided: an unknown answer must never be what
 * convinces the UI that a user's file has been deleted. Only a provider that
 * actually answered, with zero rows, counts as "gone".
 */
class UrisPresenceTest {

    @Test
    fun answeredCursorWithARow_isPresent() {
        assertTrue(Uris.presenceOf(true))
    }

    @Test
    fun answeredCursorWithNoRows_isGone() {
        assertFalse(Uris.presenceOf(false))
    }

    @Test
    fun nullCursor_isPresent() {
        // A4: a provider that returns a null cursor did not say anything about
        // the row; the shipped app treated that as "deleted".
        assertTrue(Uris.presenceOf(null))
    }
}
