package com.compressly.core.data

/**
 * A1 (CONSENT-GATE) — the *ordering* rules of the "replace original" grant flow,
 * separated from Android so they can be unit-tested on the JVM.
 *
 * The screen used to decide this inline, in a Compose lambda, which is why the
 * bug survived: there was no way to assert that a started job goes
 * legacy-storage grant -> notification question -> write consent -> delete
 * consent -> start. Here the rules are pure data-in/data-out; the screen only
 * performs the step this object returns.
 */
object ConsentGate {

    /** What still has to be asked for. Order matters: see [firstStep]. */
    enum class Step {
        /** Nothing to ask: start (or continue) the job. */
        NONE,

        /** Ask for the MediaStore edit grant for rows we will overwrite. */
        WRITE,

        /** Ask to delete rows we could only replace by publishing a new row. */
        DELETE
    }

    /**
     * The two halves of a plan: rows we may overwrite in place ([write]) and rows
     * whose original must be removed after the new row is published ([delete]).
     * Generic so tests do not need an Android `Uri`.
     */
    data class Plan<T>(val write: List<T>, val delete: List<T>) {
        val isEmpty: Boolean get() = write.isEmpty() && delete.isEmpty()
        /** Rows the dialog will mention, counted once each. */
        val dialogCount: Int get() = (write + delete).distinct().size
    }

    /**
     * Splits those rows for which the app is NOT the owner. A row we can replace
     * in place needs the edit grant; a row we cannot (unknown MIME, different
     * media type) can only be deleted, which the edit grant does not cover.
     */
    fun <T> split(foreign: List<T>, inPlacePossible: (T) -> Boolean): Plan<T> {
        val (overwritable, replaceable) = foreign.partition(inPlacePossible)
        return Plan(write = overwritable, delete = replaceable.distinct())
    }

    /**
     * First dialog to show. WRITE always wins over DELETE: the write grant is the
     * one that makes "replace" mean replace, and a delete-only dialog for a batch
     * that also has overwritable rows would delete files it could have kept.
     */
    fun firstStep(writeCount: Int, deleteCount: Int): Step = when {
        writeCount > 0 -> Step.WRITE
        deleteCount > 0 -> Step.DELETE
        else -> Step.NONE
    }

    fun <T> firstStep(plan: Plan<T>): Step = firstStep(plan.write.size, plan.delete.size)

    /**
     * What to do after an answer. Both answers continue the flow — a refusal is
     * not an error, it only means the originals stay — so the only remaining
     * question is whether a second dialog is still owed.
     */
    fun afterWrite(deleteCount: Int): Step = if (deleteCount > 0) Step.DELETE else Step.NONE
}
