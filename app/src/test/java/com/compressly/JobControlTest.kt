package com.compressly

import com.compressly.core.engine.JobControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobControlTest {

    @Test
    fun checkActive_returnsImmediatelyWhenRunning() = runTest {
        val c = JobControl()
        c.checkActive() // must not suspend/throw
    }

    @Test
    fun pause_blocksUntilResume() = runTest {
        val c = JobControl()
        c.pause()
        var resumed = false
        val job = launch {
            c.checkActive()
            resumed = true
        }
        // give the coroutine a chance to enter the pause loop
        delay(50)
        assertFalse("should be paused", resumed)
        c.resume()
        delay(100)
        assertTrue("should resume", resumed)
        job.join()
    }

    @Test
    fun cancel_throwsCompressionCancelled() = runTest {
        val c = JobControl()
        c.cancel()
        try {
            c.checkActive()
            throw AssertionError("expected CompressionCancelledException")
        } catch (e: com.compressly.core.engine.CompressionCancelledException) {
            // expected
        }
    }

    @Test
    fun cancel_whilePausedStillThrows() = runTest {
        val c = JobControl()
        c.pause()
        c.cancel()
        try {
            c.checkActive()
            throw AssertionError("expected CompressionCancelledException")
        } catch (e: com.compressly.core.engine.CompressionCancelledException) {
            // expected
        }
    }
}
