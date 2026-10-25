package com.compressly.core.service

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The app-wide limit on how many files are compressed at the same time.
 *
 * Two problems met here:
 *
 *  · **B9 (no global queue).** Every job used to get its own semaphore, so two
 *    batches started from Home ran *both* at full width: two video transcodes
 *    plus a photo batch competing for the same hardware codecs, the same
 *    cameras of memory and the same CPU. Nothing in the app was in charge of
 *    the total.
 *  · **Concurrency request.** The product target is five files at once, which is
 *    only ever safe if it is a *global* budget: five ordinary photos are fine,
 *    five 50 MP decodes are not, five hardware video encodes are not.
 *
 * So there is one budget of [MAX_CONCURRENT_ITEMS] slots for the whole process,
 * and every item asks for as many slots as it is heavy:
 *
 *  | item                                  | slots | effective at once |
 *  |---------------------------------------|-------|-------------------|
 *  | ordinary photo / MP3                  | 1     | 5                 |
 *  | heavy photo decode (>= 16 MP)         | 5     | 1                 |
 *  | photo batch on a low-memory phone     | 2     | 2                 |
 *  | AAC (hardware encoder)                | 3     | 2                 |
 *  | video (codec-capacity aware)          | 1..5  | device dependent  |
 *
 * Slots are process-wide, so a second job started while the first is running
 * queues *behind* it instead of doubling the load.
 */
object EngineGate {

    /** How many files the app compresses at the same time. */
    const val MAX_CONCURRENT_ITEMS = 5

    private val gate = Semaphore(MAX_CONCURRENT_ITEMS)

    /** Runs [block] holding [slots] of the budget, always releasing them. */
    suspend fun <T> withSlots(slots: Int, block: suspend () -> T): T {
        val n = slots.coerceIn(1, MAX_CONCURRENT_ITEMS)
        if (n == 1) return gate.withPermit { block() }
        gate.acquire(n)
        try {
            return block()
        } finally {
            gate.release(n)
        }
    }

    /** Slots a single item needs; see the table on the object. */
    fun weightForSlots(activeAtOnce: Int): Int {
        val safe = activeAtOnce.coerceIn(1, MAX_CONCURRENT_ITEMS)
        return ((MAX_CONCURRENT_ITEMS + safe - 1) / safe).coerceIn(1, MAX_CONCURRENT_ITEMS)
    }
}
