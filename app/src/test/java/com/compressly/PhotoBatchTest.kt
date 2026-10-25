package com.compressly

import com.compressly.core.engine.photo.PhotoBatch
import com.compressly.core.service.EngineGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBatchTest {

    // ── heaviestFirst: stable descending-by-size ordering ──────────────────

    @Test
    fun `heaviest first orders by size descending`() {
        val sizes = listOf(10L, 500L, 250L, 4000L, 33L)
        val ordered = PhotoBatch.heaviestFirst(sizes) { it }
        assertEquals(listOf(4000L, 500L, 250L, 33L, 10L), ordered)
    }

    @Test
    fun `heaviest first keeps original order for ties`() {
        val sizes = listOf(100L, 1L, 100L, 100L, 2L)
        val ordered = PhotoBatch.heaviestFirst(sizes) { it }
        // The three 100L entries keep their relative order (indices 0, 2, 3).
        assertEquals(listOf(100L, 100L, 100L, 2L, 1L), ordered)
    }

    @Test
    fun `heaviest first leaves zero- and one-item batches untouched`() {
        assertEquals(emptyList<Long>(), PhotoBatch.heaviestFirst(emptyList<Long>()) { it })
        assertEquals(listOf(7L), PhotoBatch.heaviestFirst(listOf(7L)) { it })
    }

    // ── CONCURRENCY-5: five ordinary photos at a time ──────────────────────

    @Test
    fun `the budget is five files at a time`() {
        assertEquals(5, EngineGate.MAX_CONCURRENT_ITEMS)
        assertEquals(5, PhotoBatch.MAX_PHOTOS_IN_FLIGHT)
    }

    @Test
    fun `an ordinary photo takes one slot, so five run together`() {
        assertEquals(1, PhotoBatch.weightFor(12_000_000L))
        assertEquals(5, PhotoBatch.activeAtOnce(PhotoBatch.weightFor(12_000_000L)))
    }

    @Test
    fun `a heavy or unprobeable photo takes the whole budget and runs alone`() {
        assertEquals(PhotoBatch.HEAVY_WEIGHT, PhotoBatch.weightFor(48_000_000L))
        assertEquals(PhotoBatch.HEAVY_WEIGHT, PhotoBatch.weightFor(16_000_000L))
        assertEquals(PhotoBatch.HEAVY_WEIGHT, PhotoBatch.weightFor(null))
        assertEquals(1, PhotoBatch.activeAtOnce(PhotoBatch.HEAVY_WEIGHT))
    }

    @Test
    fun `a low-memory phone with a big batch runs at most two at a time`() {
        val weight = PhotoBatch.weightFor(12_000_000L, lowMemoryLargeBatch = true)
        assertEquals(PhotoBatch.LOW_MEMORY_WEIGHT, weight)
        assertEquals(2, PhotoBatch.activeAtOnce(weight))
    }

    @Test
    fun `a heavy photo still runs alone on a low-memory phone`() {
        assertEquals(
            PhotoBatch.HEAVY_WEIGHT,
            PhotoBatch.weightFor(48_000_000L, lowMemoryLargeBatch = true)
        )
    }

    @Test
    fun `the low-memory rule needs both a small heap and a big batch`() {
        assertTrue(PhotoBatch.isLowMemoryLargeBatch(192, 200))
        assertFalse("a 256 MB heap is not the low-memory class", PhotoBatch.isLowMemoryLargeBatch(256, 200))
        assertFalse("a small batch is fine on a weak phone", PhotoBatch.isLowMemoryLargeBatch(192, 50))
        assertFalse("an unknown heap class never forces the slow path", PhotoBatch.isLowMemoryLargeBatch(null, 200))
    }
}
