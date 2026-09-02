package com.compressly

import android.graphics.Bitmap
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ir.siliksama.hajmino.R
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Visual regression: capture the main screens to /sdcard/CompresslyScreenshots.
 * The CI workflow pulls them and pixel-diffs against the committed baseline
 * in docs/screenshots-baseline (first CI capture commits the baseline, later
 * runs compare with a 3% drift threshold).
 *
 * Captures: onboarding page 1 (first run), home, history. Driven via
 * UiAutomator on a FRESH install — the CI runs this pass first, before any
 * other test completes onboarding.
 */
@AndroidJUnit4
class ScreenshotRegressionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private val appPackage get() = context.packageName

    private val outDir = File(Environment.getExternalStorageDirectory(), "CompresslyScreenshots")

    private fun launchApp() {
        device.pressHome()
        context.packageManager.getLaunchIntentForPackage(appPackage)
            ?.let { context.startActivity(it) }
        device.wait(Until.hasObject(By.pkg(appPackage).depth(0)), 30_000)
    }

    private fun snap(name: String) {
        outDir.mkdirs()
        device.takeScreenshot().use { bmp: Bitmap ->
            File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun appWindowHasText(text: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(By.pkg(appPackage).text(text)).waitForExists(1_000)) return true
            if (device.findObject(By.pkg(appPackage).desc(text)).waitForExists(500)) return true
        }
        return false
    }

    private fun clickAppText(text: String, seconds: Long = 15) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            val byText = device.findObject(By.pkg(appPackage).text(text))
            if (byText.waitForExists(1_500)) {
                byText.click(); device.waitForIdle(); return
            }
            val byDesc = device.findObject(By.pkg(appPackage).desc(text))
            if (byDesc.waitForExists(500)) {
                byDesc.click(); device.waitForIdle(); return
            }
        }
        throw AssertionError("UI: \"$text\" never appeared within ${seconds}s")
    }

    @Test
    fun capture_main_screens_for_visual_regression() {
        launchApp()
        val next = context.getString(R.string.onboard_next)

        // First run → onboarding page 1.
        if (appWindowHasText(next, 30)) {
            device.waitForIdle()
            snap("01_onboarding.png")
            repeat(4) { clickAppText(next) }
            clickAppText(context.getString(R.string.onboard_start))
            device.waitForIdle()
        }

        // Home
        org.junit.Assert.assertTrue(
            "home screen did not appear",
            appWindowHasText(context.getString(R.string.home_compress_photo), 20)
        )
        device.waitForIdle()
        snap("02_home.png")

        // History (icon entry point with a contentDescription)
        clickAppText(context.getString(R.string.history_title))
        device.waitForIdle()
        snap("03_history.png")

        File(outDir, "MANIFEST.txt").writeText("01_onboarding.png\n02_home.png\n03_history.png\n")
    }
}
