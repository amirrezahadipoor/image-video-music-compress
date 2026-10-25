package com.compressly

import com.compressly.core.data.ConsentGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1 (test gap) — the "replace original" grant ordering (audit item A1).
 *
 * These are the rules that decide whether a job asks MediaStore for the edit
 * grant, whether it asks to delete afterwards, and whether it starts without
 * asking at all. They used to live inline in a Compose lambda, so the bug that
 * let a job skip the consent dialog entirely could not be caught by a test.
 */
class ConsentGateTest {

    private val overwritable = listOf("a.jpg", "b.jpg")
    private val deleteOnly = listOf("c.heic", "d.heic")

    // ── split ────────────────────────────────────────────────────────────

    @Test
    fun split_putsInPlaceRowsInWriteAndTheRestInDelete() {
        val plan = ConsentGate.split(listOf("a.mp4", "b.mp4", "c.mp4")) { it != "c.mp4" }
        assertEquals(listOf("a.mp4", "b.mp4"), plan.write)
        assertEquals(listOf("c.mp4"), plan.delete)
    }

    @Test
    fun split_isEmptyWhenNothingIsForeign() {
        val plan = ConsentGate.split(emptyList<String>(), inPlacePossible = { true })
        assertTrue(plan.isEmpty)
        assertEquals(ConsentGate.Step.NONE, ConsentGate.firstStep(plan))
    }

    @Test
    fun split_deduplicatesTheDeleteSet() {
        val plan = ConsentGate.split(listOf("x", "x", "y")) { false }
        assertEquals(listOf("x", "y"), plan.delete)
    }

    @Test
    fun dialogCount_doesNotDoubleCountARowInBothSets() {
        val plan = ConsentGate.Plan(write = listOf("a", "b"), delete = listOf("b", "c"))
        assertEquals(3, plan.dialogCount)
    }

    // ── ordering ─────────────────────────────────────────────────────────

    @Test
    fun firstStep_isWriteWhenAnythingCanBeOverwritten() {
        assertEquals(ConsentGate.Step.WRITE, ConsentGate.firstStep(2, 2))
        // A delete-only dialog for a batch that also has overwritable rows would
        // delete files it could have kept — WRITE always wins.
        assertEquals(ConsentGate.Step.WRITE, ConsentGate.firstStep(1, 99))
    }

    @Test
    fun firstStep_isDeleteWhenOnlyDeletionIsPossible() {
        assertEquals(ConsentGate.Step.DELETE, ConsentGate.firstStep(0, 3))
    }

    @Test
    fun firstStep_isNoneWhenThereIsNothingToAsk() {
        assertEquals(ConsentGate.Step.NONE, ConsentGate.firstStep(0, 0))
    }

    @Test
    fun firstStep_fromAPlanMatchesTheCountsVersion() {
        val plan = ConsentGate.Plan(write = overwritable, delete = deleteOnly)
        assertEquals(ConsentGate.Step.WRITE, ConsentGate.firstStep(plan))
        assertEquals(
            ConsentGate.Step.DELETE,
            ConsentGate.firstStep(ConsentGate.Plan(write = emptyList(), delete = deleteOnly))
        )
    }

    @Test
    fun afterWrite_asksForDeleteOnlyWhenAnOriginalMustBeRemoved() {
        assertEquals(ConsentGate.Step.DELETE, ConsentGate.afterWrite(deleteOnly.size))
        assertEquals(ConsentGate.Step.NONE, ConsentGate.afterWrite(0))
    }

    /**
     * The whole trip, the way the screen walks it: write dialog (if any) ->
     * delete dialog (if any) -> start. A refusal at either step only skips the
     * rest of the questions; the job still starts.
     */
    @Test
    fun fullFlow_reachesNONEforEveryCombinationOfAnswers() {
        for (w in 0..2) for (d in 0..2) for (writeGranted in listOf(true, false)) {
            var steps = 0
            var step = ConsentGate.firstStep(w, d)
            while (step != ConsentGate.Step.NONE && steps < 5) {
                steps++
                step = when {
                    step == ConsentGate.Step.WRITE -> if (writeGranted) {
                        ConsentGate.afterWrite(d)
                    } else {
                        // A refusal still continues; it simply stops asking.
                        ConsentGate.Step.NONE
                    }
                    else -> ConsentGate.Step.NONE
                }
            }
            assertEquals("w=$w d=$d granted=$writeGranted", ConsentGate.Step.NONE, step)
            assertTrue("loop must terminate: w=$w d=$d", steps <= 2)
        }
    }
}
