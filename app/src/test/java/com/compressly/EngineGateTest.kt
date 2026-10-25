package com.compressly

import com.compressly.core.service.EngineGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global five-slot budget (audit items B9 + CONCURRENCY-5).
 *
 * [ConcurrencyBudgetTest] covers the arithmetic of the weights; this class
 * covers the gate itself, because it is hand-written: kotlinx's `Semaphore`
 * takes one permit at a time and a heavy item needs the whole budget, so the
 * accounting is a FIFO of waiters under a mutex. That is exactly the kind of
 * code that deadlocks or leaks a slot without anyone noticing, so it is
 * exercised with real coroutines.
 *
 * Bodies run on [Dispatchers.IO] on purpose: `Dispatchers.Default` is sized to
 * the number of cores (2 on a CI runner), and a test that measures how many
 * items run *at once* would then only ever see the core count, not the gate.
 */
class EngineGateTest {

    /** Counts how many bodies are inside the gate at the same time. */
    private class Probe {
        private val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        private val finished = AtomicInteger(0)

        val running: Int get() = inside.get()
        val completed: Int get() = finished.get()

        fun enter() {
            val now = inside.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
        }

        fun exit() {
            inside.decrementAndGet()
            finished.incrementAndGet()
        }
    }

    private fun runAll(
        weights: List<Int>,
        bodyMillis: Long = 120,
        beforeStart: (Int) -> Unit = {}
    ): Probe = runBlocking {
        val probe = Probe()
        weights.mapIndexed { index, weight ->
            async(Dispatchers.IO) {
                beforeStart(index)
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
    fun aHeavyItemNeverSharesTheBudget() = runBlocking {
        // Deterministic: start the heavy item and wait until it is INSIDE, then
        // release six light ones. During that window nothing else may enter.
        val heavyInside = CompletableDeferred<Unit>()
        val heavyDone = AtomicBoolean(false)
        val otherSawHeavyRunning = AtomicBoolean(false)
        val probe = Probe()

        val heavy = async(Dispatchers.IO) {
            EngineGate.withSlots(EngineGate.MAX_CONCURRENT_ITEMS) {
                probe.enter()
                try {
                    heavyInside.complete(Unit)
                    Thread.sleep(200)
                } finally {
                    probe.exit()
                    heavyDone.set(true)
                }
            }
        }
        heavyInside.await()

        val lights = (1..6).map {
            async(Dispatchers.IO) {
                if (!heavyDone.get() && probe.running > 0) {
                    // Entering while the heavy item holds everything would mean
                    // the gate handed out a slot that was not free.
                    otherSawHeavyRunning.compareAndSet(false, probe.running > 1)
                }
                EngineGate.withSlots(1) {
                    probe.enter()
                    try { Thread.sleep(40) } finally { probe.exit() }
                }
            }
        }
        heavy.await()
        lights.awaitAll()

        assertEquals("the heavy item must run alone", 1, probe.peak.get())
        assertTrue("a light item entered while the heavy one was running", !otherSawHeavyRunning.get())
    }

    @Test
    fun twoItemsThatEachNeedTwoSlotsRunAsAPair() {
        val probe = runAll(List(6) { 2 }, bodyMillis = 100)
        assertEquals(6, probe.completed)
        assertEquals("2 + 2 = 4 fits, 2 + 2 + 2 does not", 2, probe.peak.get())
    }

    @Test
    fun slotsAreReleasedEvenWhenTheBodyThrows() = runBlocking {
        repeat(EngineGate.MAX_CONCURRENT_ITEMS) {
            runCatching { EngineGate.withSlots(1) { error("boom") } }
        }
        val probe = Probe()
        (1..EngineGate.MAX_CONCURRENT_ITEMS).map {
            async(Dispatchers.IO) {
                EngineGate.withSlots(1) {
                    probe.enter()
                    try { Thread.sleep(120) } finally { probe.exit() }
                }
            }
        }.awaitAll()
        assertEquals(
            "a throwing body must give its slots back",
            EngineGate.MAX_CONCURRENT_ITEMS,
            probe.peak.get()
        )
    }

    @Test
    fun aQueuedHeavyItemIsWokenUpAfterTheLightsDrain() = runBlocking {
        // Fill the budget, queue a heavy one behind it, then drain: if the FIFO
        // wake-up were wrong this hangs (the test times out) instead of failing
        // an assertion.
        val lights = (1..EngineGate.MAX_CONCURRENT_ITEMS).map {
            async(Dispatchers.IO) {
                EngineGate.withSlots(1) { Thread.sleep(80) }
                "light"
            }
        }
        val heavy = async(Dispatchers.IO) {
            EngineGate.withSlots(EngineGate.MAX_CONCURRENT_ITEMS) { "heavy" }
        }
        val results = lights.awaitAll() + heavy.await()
        assertEquals(EngineGate.MAX_CONCURRENT_ITEMS + 1, results.size)
        assertTrue("the queued heavy item must have run", "heavy" in results)
    }

    @Test
    fun weightsOutsideTheBudgetAreClampedInsteadOfCrashing() {
        val probe = runAll(listOf(0, -4, 99), bodyMillis = 40)
        assertEquals(3, probe.completed)
        assertTrue(
            "clamped weights stay inside the gate",
            probe.peak.get() in 1..EngineGate.MAX_CONCURRENT_ITEMS
        )
    }

    @Test
    fun manyItemsDoNotLeakSlots() = runBlocking {
        // 100 items over a budget of five: if a release were ever missed, the
        // tail would never run and this would hang.
        val done = AtomicInteger(0)
        (1..100).map {
            launch(Dispatchers.IO) {
                EngineGate.withSlots(1) { done.incrementAndGet() }
            }
        }.forEach { it.join() }
        assertEquals(100, done.get())
    }
}
