package com.compressly

import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.ItemState
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.JobStatus
import com.compressly.core.engine.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class JobModelsTest {

    private fun item(id: Long, phase: ItemPhase, fraction: Float = 0f) =
        ItemState(itemId = id, fileName = "f$id.jpg", phase = phase, fraction = fraction)

    @Test
    fun `weighted fraction follows the shared phase weights`() {
        assertEquals(0f, item(1, ItemPhase.QUEUED).weightedFraction, 1e-4f)
        assertEquals(0.05f, item(1, ItemPhase.PREPARING, 0f).weightedFraction, 1e-4f)
        assertEquals(0.15f, item(1, ItemPhase.COMPRESSING, 0f).weightedFraction, 1e-4f)
        assertEquals(0.53f, item(1, ItemPhase.COMPRESSING, 0.475f).weightedFraction, 1e-4f)
        assertEquals(0.95f, item(1, ItemPhase.FINALIZING, 0f).weightedFraction, 1e-4f)
        assertEquals(1f, item(1, ItemPhase.DONE).weightedFraction, 1e-4f)
        assertEquals(0f, item(1, ItemPhase.FAILED).weightedFraction, 1e-4f)
        assertEquals(0f, item(1, ItemPhase.CANCELLED).weightedFraction, 1e-4f)
    }

    @Test
    fun `overall fraction is the mean of item weighted fractions`() {
        val job = JobState(
            jobId = 9L,
            mediaType = MediaType.PHOTO,
            status = JobStatus.RUNNING,
            items = listOf(
                item(1, ItemPhase.DONE),
                item(2, ItemPhase.FAILED),
                item(3, ItemPhase.COMPRESSING, 0.5f)
            )
        )
        // (1.0 + 0.0 + 0.55) / 3
        assertEquals(0.5166667f, job.overallFraction, 1e-4f)
    }
}
