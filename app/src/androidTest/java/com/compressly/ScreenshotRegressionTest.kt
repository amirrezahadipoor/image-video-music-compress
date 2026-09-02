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
import java.util.concurrent.TimeUnit

/**
 * Visual regression: drive the app screen by screen (onboarding, home,
 * history) and hold on each screen while the CI workflow captures it with
 * `adb exec-out screencap` (handshake via the compressly.snap_target system
 * property). The host pixel-diffs captures against the committed baseline in
 * docs/screenshots-baseline (first CI capture commits the baseline, later
 * runs compare with a 3% drift threshold).
 *
 * Driven via UiAutomator on a FRESH install — the CI runs this pass first,
 * before any other test completes onboarding.
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

    // Visual-regression capture goes through a shell property handshake
    // (see snap()): the CI capture loop reads `compressly.snap_target` and
    // takes a full-screen `adb exec-out screencap` while the test HOLDS on
    // the current screen. No file has to travel between the app uid and
    // the host at all, which sidesteps every API 30+ storage wall (scoped
    // storage, SELinux hiding app data from adb, /sdcard root unwritable).

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

    // The CI capture loop names every frame frame-<host-epoch-ms>.png and
    // echoes `frame <host-epoch-ms>` to the device logcat (tag CompresslyCap),
    // so the test can read the HOST clock from inside the device. Bracketing
    // a 20s hold with two such reads yields a window of host milliseconds;
    // the host picks the mid-window frame afterwards. One clock, no
    // handshake, no cross-process line ordering to get wrong.
    private fun latestFrameTs(): Long? = runCatching {
        val out = device.executeShellCommand("logcat -d -s CompresslyCap:I | tail -1")
        Regex("frame (\\d+)").find(out)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    private fun snap(name: String) {
        device.waitForIdle()
        val t0 = latestFrameTs()
        if (t0 == null) {
            println("COMPRESSLY-WINDOW $name MISSING-CAPTURE-LOOP")
            return
        }
        Thread.sleep(20_000)
        device.waitForIdle()
        val t1 = latestFrameTs() ?: return
        println("COMPRESSLY-WINDOW $name $t0 $t1")
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

    }
}
