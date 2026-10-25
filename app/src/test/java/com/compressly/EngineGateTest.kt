package com.compressly

import com.compressly.core.service.EngineGate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global five-slot budget (audit items B9 + CONCURRENCY-5).
 *
 * `ConcurrencyBudgetTest` covers the arithmetic of the weights; this class
 * covers the gate itself, because it is hand-written: kotlinx's `Semaphore`
 * takes one permit at a time and a heavy item needs the whole budget, so the
 * accounting is a FIFO of waiters under a mutex. That is exactly the kind of
 * code that deadlocks or leaks a slot without anyone noticing, so it is tested
 * with real coroutines on a real dispatcher.
 */
class EngineGateTest {

    /** Tracks how many bodies are inside the gate at the same time. */
    private class Probe {
        private val now = AtomicInteger(0)
        val peak = AtomicInteger(0)
        private val finished = AtomicInteger(0)

        fun enter() {
            val running = now.incrementAndGet()
            peak.updateAndGet { maxOf(it, running) }
        }

        fun exit() {
            now.decrementAndGet()
            finished.incrementAndGet()
        }

        val completed: Int get() = finished.get()
    }

    private fun runAll(weights: List<Int>, bodyMillis: Long = 60): Probe = runBlocking {
        val probe = Probe()
        weights.map { weight ->
            async(Dispatchers.Default) {
                EngineGate.withSlots(weight) {
                    probe.enter()
                    try {
                        Thread.sleep(bodyMillis)
                    } finally {
                        probe.exit()
                    }
                }
            }
        }.awaitAll()
        probe
    }

    @Test
    fun fiveOrdinaryFilesRunAtTheSameTime() {
        val probe = runAll(List(20) { 1 })
        assertEquals("all items must finish", 20, probe.completed)
        assertEquals("five slots means five bodies at most", 5, probe.peak.get())
    }

    @Test
    fun aHeavyItemNeverSharesTheBudget() {
        // One heavy item (5 slots) plus a queue of light ones: while the heavy
        // body runs nothing else may be inside.
        val probe = runAll(listOf(5, 1, 1, 1, 1, 1, 1), bodyMillis = 40)
        assertEquals(7, probe.completed)
        assertEquals("a heavy item must run alone", 1, probe.peak.get())
    }

    @Test
    fun twoItemsThatEachNeedThreeSlotsRunAsAPair() {
        val probe = runAll(List(6) { 3 }, bodyMillis = 50)
        assertEquals(6, probe.completed)
        assertEquals("3 + 3 > 5, so exactly two", 2, probe.peak.get())
    }

    @Test
    fun slotsAreReleasedEvenWhenTheBodyThrows() = runBlocking {
        repeat(EngineGate.MAX_CONCURRENT_ITEMS) {
            runCatching {
                EngineGate.withSlots(1) { error("boom") }
            }
        }
        // Every slot must be back: five ordinary items still fit.
        val probe = Probe()
        (1..EngineGate.MAX_CONCURRENT_ITEMS).map {
            async(Dispatchers.Default) {
                EngineGate.withSlots(1) {
                    probe.enter(); try { Thread.sleep(60) } finally { probe.exit() }
                }
            }
        }.awaitAll()
        assertEquals(EngineGate.MAX_CONCURRENT_ITEMS, probe.peak.get())
    }

    @Test
    fun aQueuedItemIsNotLostWhenTheGateIsBusy() = runBlocking {
        // Fill the budget, queue a heavy one behind it, then let everything
        // drain: if the FIFO wake-up were wrong this would hang (the test times
        // out) instead of failing an assertion.
        val probes = List(EngineGate.MAX_CONCURRENT_ITEMS) { Probe() }
        val light = probes.mapIndexed { index, probe ->
            async(Dispatchers.Default) {
                EngineGate.withSlots(1) {
                    probe.enter(); try { Thread.sleep(80) } finally { probe.exit() }
                }
                index
            }
        }
        val heavy = async(Dispatchers.Default) {
            EngineGate.withSlots(EngineGate.MAX_CONCURRENT_ITEMS) { 99 }
        }
        val results = light.awaitAll() + heavy.await()
        assertEquals(EngineGate.MAX_CONCURRENT_ITEMS + 1, results.size)
        assertTrue("the heavy item must have run", 99 in results)
    }

    @Test
    fun weightsOutsideTheBudgetAreClampedInsteadOfCrashing() {
        val probe = runAll(listOf(0, -4, 99), bodyMillis = 20)
        assertEquals(3, probe.completed)
        assertTrue("clamped weights stay inside the gate", probe.peak.get() in 1..EngineGate.MAX_CONCURRENT_ITEMS)
    }
}
