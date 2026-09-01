package com.compressly

import com.compressly.core.engine.photo.PhotoBatch
import org.junit.Assert.assertEquals
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

    // ── concurrencyFor: memory-aware slot count ─────────────────────────────

    @Test
    fun `ordinary photo batch keeps two slots`() {
        assertEquals(2, PhotoBatch.concurrencyFor(listOf(12_000_000L, 9_000_000L)))
        assertEquals(2, PhotoBatch.concurrencyFor(emptyList()))
    }

    @Test
    fun `huge source falls back to a single slot`() {
        assertEquals(1, PhotoBatch.concurrencyFor(listOf(12_000_000L, 48_000_000L)))
        assertEquals(1, PhotoBatch.concurrencyFor(listOf(16_000_000L)))
    }

    @Test
    fun `unprobeable source falls back to a single slot`() {
        assertEquals(1, PhotoBatch.concurrencyFor(listOf(12_000_000L, null)))
        assertEquals(1, PhotoBatch.concurrencyFor(listOf(null, null)))
    }

    // ── memory-class rule: large batches on low-memory phones ───────────────

    @Test
    fun `large photo batch on a 192MB phone runs one at a time`() {
        val ordinary = List(200) { 12_000_000L }
        assertEquals(1, PhotoBatch.concurrencyFor(ordinary, memoryClassMb = 192, batchSize = 200))
    }

    @Test
    fun `same large batch on a 256MB phone keeps both slots`() {
        val ordinary = List(200) { 12_000_000L }
        assertEquals(2, PhotoBatch.concurrencyFor(ordinary, memoryClassMb = 256, batchSize = 200))
    }

    @Test
    fun `small batch on a low-memory phone still runs in parallel`() {
        val ordinary = List(50) { 12_000_000L }
        assertEquals(2, PhotoBatch.concurrencyFor(ordinary, memoryClassMb = 192, batchSize = 50))
    }

    @Test
    fun `a heavy photo wins over the memory-class rule`() {
        val heavy = listOf(12_000_000L, 48_000_000L) + List(198) { 9_000_000L }
        assertEquals(1, PhotoBatch.concurrencyFor(heavy, memoryClassMb = 256, batchSize = 200))
    }

    @Test
    fun `defaults keep the historical behaviour`() {
        assertEquals(2, PhotoBatch.concurrencyFor(listOf(12_000_000L, 9_000_000L)))
        assertEquals(2, PhotoBatch.concurrencyFor(List(999) { 12_000_000L }))
    }
}
