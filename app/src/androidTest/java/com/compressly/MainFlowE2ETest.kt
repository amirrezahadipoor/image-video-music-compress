package com.compressly

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * E2E: the complete production journey on the CI emulator.
 *
 *   fresh install → onboarding (5 pages) → home → real system photo
 *   picker (Photos UI, driven via UiAutomator) → settings screen with the
 *   picked file → start → progress → result → history.
 *
 * Every step goes through the SAME code paths a user hits — real
 * navigation, real MediaStore insert, real PhotoCompressor, real history
 * write. Pixel-exact rendering of the OS picker itself is not asserted
 * here (that is the screenshot regression test's job).
 */
@AndroidJUnit4
class MainFlowE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext

    @get:Rule
    val compose = createAndroidComposeRule(MainActivity::class.java)

    /** Poll a Compose node by text until it appears or the deadline hits. */
    private fun waitComposeText(text: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = false
        while (!seen && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            seen = compose.onAllNodes(hasText(text), true).fetchSemanticsNodes().isNotEmpty()
            if (!seen) Thread.sleep(250)
        }
        check(seen) { "expected text \"$text\" to appear within ${timeoutMs}ms" }
    }

    /** Wait until a UiAutomator selector is clickable, else fail clearly. */
    private fun waitClickable(sel: By, what: String, seconds: Long) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(sel).waitForExists(3_000)) return
        }
        check(false) { "Picker: \"$what\" never appeared within ${seconds}s" }
    }

    /** Insert a noisy 640x480 JPEG into MediaStore for the picker to offer. */
    private fun insertPhoto(name: String, seed: Long): Uri {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            val rnd = Random(seed)
            val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            bmp.setPixels(
                IntArray(640 * 480) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) },
                0, 640, 0, 0, 640, 480
            )
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        context.contentResolver.update(
            uri,
            android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null, null
        )
        return uri
    }

    @Test
    fun fullJourney_onboarding_picker_compress_result_history() {
        // ── 0. A real photo in MediaStore for the picker to offer ───────
        val photoName = "e2e_test_photo.jpg"
        insertPhoto(photoName, seed = 7)

        // ── 1. First launch → onboarding carousel ───────────────────────
        // Cold first launch on CI (splash + dexopt + DataStore first read)
        // can take a while — wait, don't assume a 1s assertion window.
        waitComposeText(context.getString(R.string.onboard_next), 45_000)

        repeat(4) {
            compose.onNodeWithText(context.getString(R.string.onboard_next)).performClick()
            device.waitForIdle()
        }
        compose.onNodeWithText(context.getString(R.string.onboard_start)).performClick()

        // ── 2. Home ─────────────────────────────────────────────────────
        waitComposeText(context.getString(R.string.home_compress_photo), 30_000)

        // ── 3. Open the REAL system photo picker ────────────────────────
        compose.onNodeWithText(context.getString(R.string.home_compress_photo)).performClick()

        // Photos UI needs a moment to index the freshly-inserted photo.
        val select = By.text("Select").or(By.desc("Select"))
        waitClickable(By.desc(photoName), "the inserted photo", seconds = 45)
        device.findObject(By.desc(photoName)).click()
        waitClickable(select, "the Select button", seconds = 20)
        device.findObject(select).click()

        // ── 4. Back in the app: settings screen with the picked file ────
        waitComposeText(context.getString(R.string.action_compress), 20_000)
        compose.onNodeWithText(context.getString(R.string.action_compress))
            .assertIsDisplayed()

        // ── 5. Start → progress → (auto) result ─────────────────────────
        compose.onNodeWithText(context.getString(R.string.action_compress)).performClick()
        waitComposeText(context.getString(R.string.progress_title), 15_000)

        // The progress screen navigates to the result screen when the
        // single-item job completes. A 640x480 JPEG compresses fast, but
        // the emulator + first-run dexopt make this generous on purpose.
        waitComposeText(context.getString(R.string.result_view_history), 120_000)

        // ── 6. History: the entry with the real file name ───────────────
        compose.onNodeWithText(context.getString(R.string.result_view_history)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_title)).assertIsDisplayed()
        waitComposeText(photoName, 15_000)
    }
}
