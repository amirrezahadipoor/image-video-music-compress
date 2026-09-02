package com.compressly

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ir.siliksama.hajmino.R
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Accessibility audit of the REAL accessibility tree — the exact nodes
 * TalkBack reads (not an internal Compose representation).
 *
 * Rule enforced: every clickable node the screen reader can reach must be
 * labelable — it has either text or a contentDescription — otherwise the
 * control is announced as an anonymous "button" and is unusable.
 *
 * Scans: onboarding (when first-run), home, history. Violations fail the
 * build with the exact node paths, so a11y cannot regress silently.
 */
@AndroidJUnit4
class ComposeAccessibilityScanTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private val appPackage get() = context.packageName

    private fun launchApp() {
        device.pressHome()
        context.packageManager.getLaunchIntentForPackage(appPackage)
            ?.let { context.startActivity(it) }
        device.wait(Until.hasObject(By.pkg(appPackage).depth(0)), 30_000)
    }

    /** True when a node with this text or description exists in the app. */
    private fun appWindowHasText(text: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(By.pkg(appPackage).text(text)).waitForExists(1_000)) return true
            if (device.findObject(By.pkg(appPackage).desc(text)).waitForExists(500)) return true
        }
        return false
    }

    private fun clickAppNode(text: String, byDescription: Boolean = false, seconds: Long = 15) {
        val sel = if (byDescription) By.pkg(appPackage).desc(text) else By.pkg(appPackage).text(text)
        val found = device.wait(Until.findObject(sel), TimeUnit.SECONDS.toMillis(seconds))
            ?: throw AssertionError("a11y: control \"$text\" never appeared")
        found.click()
        device.waitForIdle()
    }

    /**
     * Walk the app's active window; collect clickable nodes without a
     * label. Depth/node caps keep the walk bounded against pathological
     * trees; the app's screens are small.
     */
    private fun unlabeledClickables(): List<String> {
        val violations = mutableListOf<String>()
        val window = device.windows.firstOrNull { it.packageName == appPackage }
            ?: throw AssertionError("a11y: app window not found (package $appPackage)")
        var visited = 0
        fun walk(node: UiObject2, path: String, depth: Int) {
            if (visited++ > 800 || depth > 40) return
            runCatching {
                val info = node.info
                if (info.isClickable) {
                    val text = info.text?.toString().orEmpty().trim()
                    val desc = info.contentDescription?.toString().orEmpty().trim()
                    if (text.isEmpty() && desc.isEmpty()) {
                        violations += "$path (${info.className})"
                    }
                }
            }
            try {
                val count = node.childCount
                for (i in 0 until count) {
                    node.getChildNode(i)?.let { walk(it, "$path/$i", depth + 1) }
                }
            } catch (_: Exception) {
                // node became invalid mid-walk — skip its subtree
            }
        }
        walk(window.interaction, "(root)", 0)
        return violations
    }

    private fun assertLabeled(screen: String) {
        val violations = unlabeledClickables()
        org.junit.Assert.assertTrue(
            "accessibility violations on \"$screen\":\n" + violations.joinToString("\n") { "  - $it" },
            violations.isEmpty()
        )
    }

    @Test
    fun main_screens_have_no_unlabeled_clickable_nodes() {
        launchApp()

        // First launch (fresh CI install) shows onboarding — scan it, then
        // walk through to home.
        val next = context.getString(R.string.onboard_next)
        if (appWindowHasText(next, 30)) {
            assertLabeled("onboarding")
            repeat(4) { clickAppNode(next) }
            clickAppNode(context.getString(R.string.onboard_start))
        } else {
            org.junit.Assert.assertTrue(
                "neither onboarding nor home appeared",
                appWindowHasText(context.getString(R.string.home_compress_photo), 20)
            )
        }

        // Home (just scan — clicking the photo card would open the picker)
        org.junit.Assert.assertTrue(
            "home screen did not appear",
            appWindowHasText(context.getString(R.string.home_compress_photo), 20)
        )
        assertLabeled("home")

        // History (the entry point is an icon with a contentDescription)
        clickAppNode(context.getString(R.string.history_title), byDescription = true)
        assertLabeled("history")
    }
}
