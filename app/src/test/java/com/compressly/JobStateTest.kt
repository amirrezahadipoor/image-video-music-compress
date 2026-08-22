package com.compressly

import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.ItemState
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.JobStatus
import com.compressly.core.engine.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStateTest {

    private fun item(id: Long, phase: ItemPhase, fraction: Float = 0f) =
        ItemState(id, "f$id", phase, fraction)

    @Test
    fun overallFraction_zeroWhenEmpty() {
        val s = JobState(1, MediaType.PHOTO, JobStatus.RUNNING, items = emptyList())
        assertEquals(0f, s.overallFraction, 0.001f)
    }

    @Test
    fun overallFraction_fullWhenAllDone() {
        val s = JobState(
            1, MediaType.PHOTO, JobStatus.RUNNING,
            items = listOf(item(1, ItemPhase.DONE), item(2, ItemPhase.DONE))
        )
        assertEquals(1f, s.overallFraction, 0.001f)
    }

    @Test
    fun overallFraction_halfWhenHalfDone() {
        val s = JobState(
            1, MediaType.PHOTO, JobStatus.RUNNING,
            items = listOf(item(1, ItemPhase.DONE), item(2, ItemPhase.QUEUED))
        )
        assertEquals(0.5f, s.overallFraction, 0.001f)
    }

    @Test
    fun overallFraction_monotonicPerPhase() {
        // Compressing at 50% must be ahead of preparing at 100%.
        val preparing = JobState(
            1, MediaType.PHOTO, JobStatus.RUNNING,
            items = listOf(item(1, ItemPhase.PREPARING, 1f))
        ).overallFraction
        val compressing = JobState(
            1, MediaType.PHOTO, JobStatus.RUNNING,
            items = listOf(item(1, ItemPhase.COMPRESSING, 0.5f))
        ).overallFraction
        assertTrue("compressing(50%) > preparing(100%)", compressing > preparing)
    }

    @Test
    fun failedItemsStillReachFullProgress() {
        val s = JobState(
            1, MediaType.PHOTO, JobStatus.RUNNING,
            items = listOf(item(1, ItemPhase.FAILED), item(2, ItemPhase.DONE))
        )
        assertEquals(1f, s.overallFraction, 0.001f)
    }
}
