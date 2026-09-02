package com.compressly

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ir.siliksama.hajmino.R
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * E2E: the complete production journey on the CI emulator, driven through
 * the ACCESSIBILITY tree (UiAutomator) — exactly what TalkBack and any
 * screen reader sees, and the same path a real user hits:
 *
 *   fresh install → onboarding (5 pages) → home → real system photo
 *   picker (Photos UI) → settings screen with the picked file → start →
 *   progress → result → history.
 *
 * Real MediaStore insert, real PhotoCompressor, real navigation, real
 * history write. Pixel-exact rendering of the OS picker is not asserted
 * here (that is the screenshot regression test's job).
 */
@RunWith(AndroidJUnit4::class)
class MainFlowE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private val appPackage get() = context.packageName


    // The app FORCES Persian (fa) via attachBaseContext regardless of the
    // device locale, but this test process resolves resources with the DEVICE
    // locale (en-US on CI) — so every UI assertion must read the string in
    // the FA configuration or it searches for "Next" while the screen
    // shows "بعدی".
    private fun faString(id: Int): String {
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale("fa"))
        }
        return context.createConfigurationContext(cfg).getString(id)
    }

    private fun launchApp() {
        device.pressHome()
        // Launch through the shell: an instrumented test process is a
        // background context, and Android 12+ blocks background activity
        // starts (the classic startActivity from app-context pattern dies
        // silently on API 35). `am start -W` from the shell always works.
        device.executeShellCommand("am start -W -n $appPackage/com.compressly.MainActivity")
        device.wait(Until.hasObject(By.pkg(appPackage).depth(0)), 60_000)
    }

    /** Wait for the first node matching [sel]; null on timeout. */
    private fun waitForNode(sel: BySelector, seconds: Long): UiObject2? =
        device.wait(Until.findObject(sel), TimeUnit.SECONDS.toMillis(seconds))

    private fun waitClickable(sel: BySelector, what: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (waitForNode(sel, 3) != null) return true
        }
        return false
    }

    /**
     * Click a node by its visible text (Compose exposes semantics text as
     * the accessibility text). Falls back to contentDescription for the
     * rare icon-labeled control.
     */
    private fun clickText(text: String, seconds: Long) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            val byText = waitForNode(By.text(text), 1)
            if (byText != null) {
                byText.click(); device.waitForIdle(); return
            }
            val byDesc = waitForNode(By.desc(text), 1)
            if (byDesc != null) {
                byDesc.click(); device.waitForIdle(); return
            }
        }
        throw AssertionError("UI: \"$text\" never appeared within ${seconds}s")
    }

    /** Insert a noisy 640x480 JPEG into MediaStore for the picker to offer. */
    private fun insertPhoto(name: String, seed: Long): Uri {
        val values = ContentValues().apply {
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
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null, null
        )
        return uri
    }

    @Test
    fun fullJourney_onboarding_picker_compress_result_history() {
        // ── 0. A real photo in MediaStore for the picker to offer ───────
        val photoName = "e2e_test_photo.jpg"
        insertPhoto(photoName, seed = 7)

        // ── 1. Fresh launch → onboarding carousel ───────────────────────
        launchApp()
        val next = faString(R.string.onboard_next)
        clickText(next, 60)
        repeat(4) { clickText(next, 10) }
        clickText(faString(R.string.onboard_start), 10)

        // ── 2. Home ─────────────────────────────────────────────────────
        val photoCard = faString(R.string.home_compress_photo)
        clickText(photoCard, 30)

        // ── 3. Open the REAL system photo picker ────────────────────────
        // Photos UI needs a moment to index the freshly-inserted photo.
        check(waitClickable(By.desc(photoName), "the inserted photo", seconds = 45)) {
            "Picker: the inserted photo never appeared"
        }
        waitForNode(By.desc(photoName), 10)!!.click()

        // The multi-select action button (EN device locale):
        if (waitForNode(By.desc("Select"), 8) != null) {
            waitForNode(By.desc("Select"), 10)!!.click()
        } else {
            check(waitClickable(By.text("Select"), "Select (text)", seconds = 15)) {
                "Picker: the Select button never appeared"
            }
            waitForNode(By.text("Select"), 10)!!.click()
        }

        // ── 4. Back in the app: settings screen with the picked file ────
        val compress = faString(R.string.action_compress)
        check(waitClickable(By.text(compress), "compress CTA", seconds = 30)) {
            "Back in the app: the compress CTA never appeared (picker result lost?)"
        }
        waitForNode(By.text(compress), 10)!!.click()
        device.waitForIdle()

        // ── 5. Progress → (auto) result ─────────────────────────────────
        check(waitClickable(By.text(faString(R.string.progress_title)),
            "progress title", seconds = 20)) { "progress screen never appeared" }

        // The progress screen navigates to the result screen when the
        // single-item job completes. A 640x480 JPEG is fast, but the
        // emulator + first-run dexopt make this generous on purpose.
        val viewHistory = faString(R.string.result_view_history)
        check(waitClickable(By.text(viewHistory), "result screen", seconds = 120)) {
            "result screen never appeared within 120s"
        }

        // ── 6. History: the entry with the real file name ───────────────
        waitForNode(By.text(viewHistory), 10)!!.click()
        device.waitForIdle()
        check(waitClickable(By.text(faString(R.string.history_title)),
            "history title", seconds = 15)) { "history screen never appeared" }
        check(waitClickable(By.text(photoName), "history row for the e2e photo", seconds = 15)) {
            "history never showed the compressed file"
        }
    }
}
