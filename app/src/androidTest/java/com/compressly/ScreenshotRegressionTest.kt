package com.compressly

import android.graphics.Bitmap
import android.os.Environment
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
import java.io.File

/**
 * Visual regression: capture the main screens to /sdcard/CompresslyScreenshots
 * (the CI workflow pulls them and pixel-diffs against the committed
 * baseline in docs/screenshots-baseline).
 *
 * Captures: onboarding page 1 (first run), home, history. Settings with a
 * picked file is covered by the E2E journey; the baseline set here is the
 * stable one (no picker state involved, no timing-sensitive job output).
 */
@AndroidJUnit4
class ScreenshotRegressionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext

    @get:Rule
    val compose = createAndroidComposeRule(MainActivity::class.java)

    private val outDir = File(Environment.getExternalStorageDirectory(), "CompresslyScreenshots")

    private fun snap(name: String) {
        outDir.mkdirs()
        device.takeScreenshot().use { bmp ->
            File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun composeTextVisible(text: String): Boolean =
        compose.onAllNodes(hasText(text), true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun capture_main_screens_for_visual_regression() {
        val next = context.getString(R.string.onboard_next)

        // First run → onboarding page 1.
        if (composeTextVisible(next)) {
            device.waitForIdle()
            snap("01_onboarding.png")
            repeat(4) {
                compose.onNodeWithText(next).performClick()
                device.waitForIdle()
            }
            compose.onNodeWithText(context.getString(R.string.onboard_start)).performClick()
            device.waitForIdle()
        }

        // Home
        compose.onNodeWithText(context.getString(R.string.home_compress_photo))
        device.waitForIdle()
        snap("02_home.png")

        // History
        compose.onNodeWithText(context.getString(R.string.history_title)).performClick()
        device.waitForIdle()
        snap("03_history.png")

        // The E2E journey may have left a history entry — the baseline
        // capture runs on a FRESH install in CI, so the empty state is the
        // expected baseline. (The workflow asserts the file exists, not
        // its content — content drift is caught by the pixel diff.)
        File(outDir, "MANIFEST.txt").writeText(
            "01_onboarding.png\n02_home.png\n03_history.png\n"
        )
    }
}
