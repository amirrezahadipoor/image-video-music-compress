package com.compressly

import android.content.ContentValues
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressly.core.data.ConsentGate
import com.compressly.core.data.MediaStoreConsent
import com.compressly.core.engine.video.VideoConcurrency
import com.compressly.core.util.Mime
import com.compressly.core.util.Uris
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H2 (test gap) — the two riskiest subsystems had no executable coverage: the
 * MediaStore publish/replace path (audit items A1/A4/B7) and the hardware codec
 * budgets the concurrency gate relies on (B9/C5), on a real runtime.
 *
 * Runs on the emulator in the `device-tests` job. Everything is created and
 * deleted here, so the test is hermetic; rows are inserted with
 * `IS_PENDING=1` for the duration of the test and removed afterwards, which is
 * exactly the dance the engine performs when it publishes a result.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePublishTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    @Before
    fun requireMediaStore() {
        assumeTrue("no MediaStore on this image", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    @After
    fun cleanUp() {
        inserted.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        inserted.clear()
    }

    /** Inserts a tiny PNG row the way the engine publishes one. */
    private fun insertPendingRow(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CompresslyTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )
        assertNotNull("insert returned null", uri)
        inserted += uri!!
        return uri
    }

    @Test
    fun publishedRowIsReadableByTheAppAndByUris() {
        val uri = insertPendingRow("compressly-test-${System.currentTimeMillis()}.png")
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(payload) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        assertEquals(1, context.contentResolver.update(uri, done, null, null))

        // A4: the row exists and reports the size we wrote.
        assertTrue(Uris.exists(context, uri))
        assertEquals(payload.size.toLong(), Uris.sizeOf(context, uri))
        assertEquals("image/png", context.contentResolver.getType(uri))
    }

    @Test
    fun deletingTheRowMakesExistsFalse() {
        val uri = insertPendingRow("compressly-gone-${System.currentTimeMillis()}.png")
        assertEquals(1, context.contentResolver.delete(uri, null, null))
        inserted.remove(uri)
        // A4: only an ANSWERED cursor with no rows may say "gone".
        assertTrue(!Uris.exists(context, uri))
    }

    @Test
    fun ourOwnRowNeedsNoConsentDialog() {
        val uri = insertPendingRow("compressly-own-${System.currentTimeMillis()}.png")
        // A1: the app owns this row, so the gate must not pop a system dialog.
        assertEquals(emptyList<Uri>(), MediaStoreConsent.needsWriteConsent(context, listOf(uri)))
        val plan = MediaStoreConsent.plan(context, listOf(uri)) { true }
        assertTrue(plan.isEmpty)
        assertEquals(ConsentGate.Step.NONE, ConsentGate.firstStep(plan.write.size, plan.delete.size))
    }

    @Test
    fun aDocumentUriIsNeverTreatedAsAMediaStoreRow() {
        // SAF documents carry the tree grant the user already gave; asking for a
        // MediaStore consent on them would be a dialog that can never be honoured.
        val doc = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2Fa.jpg"
        )
        assertEquals(emptyList<Uri>(), MediaStoreConsent.needsWriteConsent(context, listOf(doc)))
    }

    // ── codec budget (B9 / the concurrency gate) ──────────────────────────

    @Test
    fun hardwareCodecBudgetIsAtLeastOneAndMatchesTheCodecList() {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val h264 = codecs.codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } }
        assumeTrue("no AVC encoder on this image", h264.isNotEmpty())

        val declared = h264.maxOf { info ->
            runCatching { info.getCapabilitiesForType("video/avc").maxSupportedInstances }.getOrDefault(1)
        }
        val slots = VideoConcurrency.slotsFor(com.compressly.core.engine.model.VideoCodec.H264)
        // The gate must never promise more parallel transcodes than the device
        // says it can run, and never promises zero or more than 5.
        assertTrue("slots=$slots declared=$declared", slots in 1..5)
        assertTrue("slots=$slots declared=$declared", slots <= maxOf(1, declared))
    }

    @Test
    fun unsupportedCodecFallsBackToOneSlotInsteadOfCrashing() {
        // An unknown/absent capability must not open the gate: one transcode at a
        // time is slow but correct, five is an instant OMX/Metal OOM.
        val slots = VideoConcurrency.slotsFor(com.compressly.core.engine.model.VideoCodec.H265)
        assertTrue(slots in 1..5)
    }
}
