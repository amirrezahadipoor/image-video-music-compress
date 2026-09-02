package com.compressly

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
@RunWith(AndroidJUnit4::class)
class ScreenshotRegressionTest {

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

    // Internal storage: /sdcard root is not writable (scoped storage) and
    // the app-specific EXTERNAL dir is SELinux-invisible to adb on API 30+
    // (the pull would say "No such file or directory"). Internal files of a
    // debuggable build are pullable via `adb exec-out run-as <pkg> tar ...`.
    private val outDir = File(context.filesDir, "CompresslyScreenshots")

    private fun launchApp() {
        device.pressHome()
        // Launch through the shell: an instrumented test process is a
        // background context, and Android 12+ blocks background activity
        // starts (the classic startActivity from app-context pattern dies
        // silently on API 35). `am start -W` from the shell always works.
        device.executeShellCommand("am start -W -n $appPackage/com.compressly.MainActivity")
        device.wait(Until.hasObject(By.pkg(appPackage).depth(0)), 60_000)
    }

    private fun waitForNode(sel: BySelector, seconds: Long): UiObject2? =
        device.wait(Until.findObject(sel), TimeUnit.SECONDS.toMillis(seconds))

    private fun snap(name: String) {
        outDir.mkdirs()
        val ok = device.takeScreenshot(File(outDir, name))
        org.junit.Assert.assertTrue("screenshot $name failed", ok)
    }

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
            if (byText != null) {
                byText.click(); device.waitForIdle(); return
            }
            val byDesc = waitForNode(By.pkg(appPackage).desc(text), 1)
            if (byDesc != null) {
                byDesc.click(); device.waitForIdle(); return
            }
        }
        throw AssertionError("UI: \"$text\" never appeared within ${seconds}s")
    }

    @Test
    fun capture_main_screens_for_visual_regression() {
        launchApp()
        val next = faString(R.string.onboard_next)
        val homeText = faString(R.string.home_compress_photo)

        // The very first app start on a fresh CI emulator can spend two
        // minutes in dexopt before anything is composed — so poll for
        // WHICHEVER screen shows up (onboarding or straight-to-home)
        // within a 180s total budget, instead of betting on a fixed order.
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

        // Home
        org.junit.Assert.assertTrue(
            "neither onboarding nor home appeared (cold start timeout?)",
            appHasText(homeText, 60)
        )
        device.waitForIdle()
        snap("02_home.png")

        // History (icon entry point with a contentDescription)
        clickAppText(faString(R.string.history_title))
        device.waitForIdle()
        snap("03_history.png")

        File(outDir, "MANIFEST.txt").writeText("01_onboarding.png\n02_home.png\n03_history.png\n")
    }
}
