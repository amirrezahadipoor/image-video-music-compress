package com.compressly.core.engine

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thrown when a job is cancelled; the engine cleans up and propagates.
 * Extends RuntimeException so it flows safely through framework/library code
 * (e.g. output streams) that does not declare checked exceptions.
 */
class CompressionCancelledException : RuntimeException("Compression cancelled by user")

/**
 * Cooperative pause/cancel token passed to every engine loop.
 * Loops call [checkActive] frequently; it suspends while paused and
 * throws [CompressionCancelledException] once cancelled.
 */
class JobControl {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    fun pause() = paused.set(true)
    fun resume() = paused.set(false)

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
    }

    suspend fun checkActive() {
        if (cancelled.get()) throw CompressionCancelledException()
        while (paused.get()) {
            delay(150)
            if (cancelled.get()) throw CompressionCancelledException()
        }
    }
}
