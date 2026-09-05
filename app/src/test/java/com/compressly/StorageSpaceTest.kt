package com.compressly

import com.compressly.core.util.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPACE-MODEL-FIX (X3): the free-space gate must price the TEMP headroom of a
 * job, not just its published output — a video transcode holds the first pass
 * and possibly a corrective second pass plus the audio temp in cache while the
 * result is copied out, and a photo batch stages a full source copy. The pure
 * half of the gate is pinned here so the multiplier can never quietly regress
 * to the old 1.15x slack that let big batches start and die mid-way.
 */
class StorageSpaceTest {

    @Test
    fun zeroOrNegativeNeedRequiresNothing() {
        assertEquals(0L, Storage.requiredFreeBytes(0L))
        assertEquals(0L, Storage.requiredFreeBytes(-5L))
    }

    @Test
    fun tempHeadroomIsPricedOnTopOfTheOutput() {
        // output + 2x output of scratch room = 3x
        assertEquals(300L, Storage.requiredFreeBytes(100L))
        assertEquals(3_000_000L, Storage.requiredFreeBytes(1_000_000L))
    }

    @Test
    fun requirementGrowsMonotonically() {
        assertTrue(
            Storage.requiredFreeBytes(1_000L) < Storage.requiredFreeBytes(1_001L)
        )
    }
}
