package com.compressly.core.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *  | item                            | slots | effective at once |
 *  |---------------------------------|-------|-------------------|
 *  | ordinary photo / MP3            | 1     | 5                 |
 *  | heavy photo (>= 16 MP)          | 5     | 1                 |
 *  | photo batch on a low-memory phone | 2   | 2                 |
 *  | AAC (hardware encoder)          | 3     | 2                 |
 *  | video (codec-capacity aware)    | 1..5  | device dependent  |
 *
 * Slots are process-wide, so a second job started while the first is running
 * shares the same five slots instead of doubling the load.
 *
 * An item may need more than one slot, and `kotlinx.coroutines.sync.Semaphore`
 * can only take one permit at a time, so the accounting is explicit: a FIFO of
 * waiters plus a free-slot counter, both guarded by a [Mutex]. Grants are atomic
 * and only ever to the head of the queue, which is what makes the multi-slot
 * case deadlock-free — no two items can each hold part of what they need.
 */
object EngineGate {

    /** How many files the app compresses at the same time. */
    const val MAX_CONCURRENT_ITEMS = 5

    private val lock = Mutex()
    private var freeSlots = MAX_CONCURRENT_ITEMS
    private val waiters = ArrayDeque<Waiter>()

    private class Waiter(val want: Int, val ticket: CompletableDeferred<Unit>)

    /** Runs [block] holding [slots] of the budget, always releasing them. */
    suspend fun <T> withSlots(slots: Int, block: suspend () -> T): T {
        val wanted = slots.coerceIn(1, MAX_CONCURRENT_ITEMS)
        acquire(wanted)
        try {
            return block()
        } finally {
            release(wanted)
        }
    }

    /** Slots a single item needs when [activeAtOnce] of them may run together. */
    fun weightForSlots(activeAtOnce: Int): Int {
        val atOnce = activeAtOnce.coerceIn(1, MAX_CONCURRENT_ITEMS)
        // ceil(MAX / atOnce): 1 -> 5, 2 -> 3 (two at most), 3 -> 2, 5 -> 1.
        return (MAX_CONCURRENT_ITEMS + atOnce - 1) / atOnce
    }

    private suspend fun acquire(want: Int) {
        val ticket = CompletableDeferred<Unit>()
        lock.withLock {
            if (waiters.isEmpty() && freeSlots >= want) {
                freeSlots -= want
                ticket.complete(Unit)
            } else {
                // FIFO: somebody is already waiting, so joining the queue keeps
                // the order and stops a stream of light items from starving a
                // heavy one.
                waiters.addLast(Waiter(want, ticket))
            }
        }
        ticket.await()
    }

    private suspend fun release(count: Int) {
        lock.withLock {
            freeSlots += count
            while (waiters.isNotEmpty() && freeSlots >= waiters.first().want) {
                val next = waiters.removeFirst()
                freeSlots -= next.want
                next.ticket.complete(Unit)
            }
        }
    }
}
