package com.compressly

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.CompressionSettings
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.JobStatus
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PhotoSettings
import org.junit.Test
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * BATCH E2E: 30 photos through the REAL JobCoordinator — the production
 * pipeline end to end, no UI.
 *
 * This is the test that actually exercises the 2-way parallel photo
 * scheduling (MAX_PHOTOS_IN_FLIGHT), heaviest-first ordering, the
 * no-gain copy guard, OutputStore MediaStore writes and the history rows.
 * Unit tests cover the pieces; only a batch this size on a real runtime
 * covers the interplay.
 *
 * Sources are file:// URIs written by the test itself (which runs in the
 * app's own process): no storage permission needed, fully deterministic,
 * and the engine reads them exactly as it would a picker-granted URI
 * (contentResolver.openInputStream).
 */
@AndroidJUnit4
class PhotoBatchE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun noisyJpeg(file: File, seed: Long) {
        val rnd = Random(seed)
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bmp.setPixels(
            IntArray(640 * 480) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) },
            0, 640, 0, 0, 640, 480
        )
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    @Test
    fun batchOf30Photos_allSucceed_allSmaller_allDecodable() = runBlocking {
        val app = context.applicationContext as CompresslyApp
        val container = app.container
        val coordinator = container.jobCoordinator

        // ── 30 noisy sources (each genuinely compressible 95 → 82) ──────
        val dir = File(context.getExternalFilesDir(null), "e2e-batch").apply { mkdirs() }
        val items = (1..30).map { i ->
            val f = File(dir, "batch_${i}.jpg")
            noisyJpeg(f, seed = 100 + i.toLong())
            InputItem(
                itemId = 5000L + i,
                uri = Uri.fromFile(f),
                displayName = f.name,
                sizeBytes = f.length(),
                mediaType = MediaType.PHOTO
            )
        }
        val originalSizes = items.associate { it.itemId to it.sizeBytes }

        // ── Enqueue exactly like the UI does ────────────────────────────
        val jobId = coordinator.enqueue(
            MediaType.PHOTO,
            items,
            CompressionSettings.Photo(PhotoSettings(), CompressionPreset.BALANCED)
        )

        // ── Wait for the whole batch to finish ──────────────────────────
        withTimeout(240.seconds) {
            while (true) {
                val state = coordinator.job(jobId) ?: error("job $jobId vanished")
                when (state.status) {
                    JobStatus.COMPLETED -> break
                    JobStatus.FAILED, JobStatus.CANCELLED ->
                        error("batch ended ${state.status}: ${state.items.filter { it.error != null }}")
                    else -> delay(250)
                }
            }
        }

        // ── Every item DONE, every output smaller + decodable ───────────
        val entries = container.historyRepository.getByJob(jobId)
        org.junit.Assert.assertEquals("one history row per file", 30, entries.size)
        entries.forEach { e ->
            org.junit.Assert.assertEquals("row must be DONE (error=${e.error})", "DONE", e.status)
            val outUri = e.outputUri ?: error("row ${e.fileName} has no outputUri")
            org.junit.Assert.assertTrue(
                "output must be smaller than the noisy q95 source",
                e.outputSize < e.inputSize
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(outUri))!!.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            org.junit.Assert.assertTrue(
                "output ${e.fileName} must be a decodable bitmap (got ${bounds.outWidth}x${bounds.outHeight})",
                bounds.outWidth > 0 && bounds.outHeight > 0
            )
        }

        // ── Parallelism sanity: 30 files at 2-in-flight finished well ───
        // below the ~30x serial ceiling on a slow emulator. Not a tight
        // timing assertion (CI is noisy) — a regression to strictly
        // serial with heavy per-file work would blow past this by a lot.
        val elapsedMs = System.currentTimeMillis() - coordinator.job(jobId)!!.startedAt
        org.junit.Assert.assertTrue(
            "30-photo batch took ${elapsedMs}ms — parallel scheduling may be broken",
            elapsedMs < 180_000
        )

        // Cleanup so re-runs start clean.
        dir.deleteRecursively()
    }
}
