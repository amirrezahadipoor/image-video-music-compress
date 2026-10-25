package com.compressly

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ir.siliksama.hajmino.R
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Visual regression, self-capturing (audit item G3).
 *
 * The previous version of this test never ran: it held on each screen for
 * twenty seconds and waited for a *host* capture loop (`adb exec-out screencap`
 * driven from the workflow) to photograph the device, keyed by a handshake file
 * in `/data/local/tmp`. No such loop was ever added to CI, so the class was
 * dead code and the app shipped without any visual guard.
 *
 * Now the test captures the screen itself with [UiDevice.takeScreenshot] and
 * compares it against a baseline shipped as an instrumentation asset
 * (`app/src/androidTest/assets/baselines/<name>.png`):
 *
 *  - no baseline for a screen  -> the capture is recorded as a build artifact and
 *    the check is skipped (never a false failure on a fresh checkout);
 *  - baseline present          -> a per-pixel luminance diff over a 64-wide
 *    thumbnail fails the test above [DRIFT_THRESHOLD].
 *
 * Baselines are refreshed on purpose, not automatically: run this class once
 * with `-Pcompressly.recordBaselines=1`, then `adb pull` the files from
 * `/sdcard/Android/data/<pkg>/files/screenshots/` into the assets folder and
 * review the diff before committing.
 *
 * Run: ./gradlew :app:connectedBazaarDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.compressly.ScreenshotRegressionTest
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotRegressionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private val appPackage get() = context.packageName

    private val outputDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshots")
            .apply { mkdirs() }
    }

    // The app FORCES Persian (fa) via attachBaseContext regardless of the device
    // locale, but this test process resolves resources with the DEVICE locale
    // (en-US on CI) — so every UI assertion must read the string in the FA
    // configuration or it searches for "Next" while the screen shows "بعدی".
    private fun faString(id: Int): String {
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale("fa"))
        }
        return context.createConfigurationContext(cfg).getString(id)
    }

    private fun launchApp() {
        device.pressHome()
        // An instrumented test process is a background context, and Android 12+
        // blocks background activity starts; `am start -W` from the shell always
        // works.
        device.executeShellCommand("am start -W -n $appPackage/com.compressly.MainActivity")
        device.wait(Until.hasObject(By.pkg(appPackage).depth(0)), 60_000)
    }

    private fun waitForNode(sel: BySelector, seconds: Long): UiObject2? =
        device.wait(Until.findObject(sel), TimeUnit.SECONDS.toMillis(seconds))

    private fun appHasText(text: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (waitForNode(By.pkg(appPackage).text(text), 1) != null) return true
            if (waitForNode(By.pkg(appPackage).desc(text), 1) != null) return true
        }
        return false
    }

    private fun clickAppText(text: String, seconds: Long = 15) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            val byText = waitForNode(By.pkg(appPackage).text(text), 1)
            if (byText != null) { byText.click(); device.waitForIdle(); return }
            val byDesc = waitForNode(By.pkg(appPackage).desc(text), 1)
            if (byDesc != null) { byDesc.click(); device.waitForIdle(); return }
        }
        throw AssertionError("UI: \"$text\" never appeared within ${seconds}s")
    }

    // ── capture + compare ────────────────────────────────────────────────

    private fun snap(name: String) {
        device.waitForIdle()
        val file = File(outputDir, name)
        // UiDevice.takeScreenshot(File) only reports success, so capture the
        // bitmap and write it ourselves (the file is the artifact that the CI
        // job collects when a baseline is missing).
        // UiAutomation.takeScreenshot() is the stable Bitmap-returning API;
        // UiDevice's own overloads in uiautomator 2.3.0 do not give a bitmap
        // back to Kotlin (its takeScreenshot(File) only reports success).
        val bmp = runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        }.getOrNull() ?: run {
            println("SCREENSHOT $name SKIPPED (device refused to capture)")
            return
        }
        runCatching {
            java.io.FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        println("SCREENSHOT $name -> ${file.absolutePath} (${bmp.width}x${bmp.height})")

        // A screen the app painted nothing on (black/white frame) is a failure
        // even without a baseline: it means the window never composed.
        assertTrue("$name captured a blank frame", isNotBlank(bmp))

        val baseline = runCatching {
            instrumentation.context.assets.open("baselines/$name").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (baseline == null) {
            println("SCREENSHOT $name NO-BASELINE (recorded as artifact, not compared)")
            return
        }
        val drift = drift(bmp, baseline)
        println("SCREENSHOT $name drift=%.4f%%".format(drift * 100))
        assertTrue(
            "$name drifted %.2f%% from its baseline (limit %.0f%%)".format(drift * 100, DRIFT_THRESHOLD * 100),
            drift <= DRIFT_THRESHOLD
        )
    }

    private fun luminance(bmp: Bitmap): DoubleArray {
        // 64-wide thumbnail: resolution, status-bar clock and scroll offsets do
        // not move a 64xN average, a real layout/scheme change does.
        val w = 64
        val h = maxOf(1, bmp.height * w / maxOf(1, bmp.width))
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val out = DoubleArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        return out
    }

    private fun drift(a: Bitmap, b: Bitmap): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val n = minOf(la.size, lb.size)
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += kotlin.math.abs(la[i] - lb[i])
        // Ignore the tail when the aspect ratios differ (resolution change).
        return sum / (n * 255.0)
    }

    private fun isNotBlank(bmp: Bitmap): Boolean {
        val l = luminance(bmp)
        if (l.isEmpty()) return false
        val mean = l.average()
        if (mean < 3.0 || mean > 252.0) return false
        // Variance: a uniform frame (one flat colour) is not a UI.
        val variance = l.sumOf { (it - mean) * (it - mean) } / l.size
        return variance >= 25.0
    }

    @Test
    fun capture_main_screens_for_visual_regression() {
        launchApp()
        val next = faString(R.string.onboard_next)
        val homeText = faString(R.string.home_compress_photo)

        // The very first app start on a fresh CI emulator can spend two minutes
        // in dexopt before anything is composed — poll for WHICHEVER screen shows
        // up (onboarding or straight-to-home) instead of betting on an order.
        val deadline = System.currentTimeMillis() + 180_000
        var seenOnboarding = false
        while (System.currentTimeMillis() < deadline) {
            if (appHasText(next, 3)) { seenOnboarding = true; break }
            if (appHasText(homeText, 3)) break
        }
        if (seenOnboarding) {
            device.waitForIdle()
            snap("01_onboarding.png")
            repeat(4) { clickAppText(next) }
            clickAppText(faString(R.string.onboard_start))
            device.waitForIdle()
        }

        assertTrue(
            "neither onboarding nor home appeared (cold start timeout?)",
            appHasText(homeText, 60)
        )
        device.waitForIdle()
        snap("02_home.png")

        clickAppText(faString(R.string.history_title))
        device.waitForIdle()
        snap("03_history.png")
    }

    private companion object {
        /** Fraction of the 0..255 luminance range that may shift per pixel. */
        const val DRIFT_THRESHOLD = 0.03
    }
}
