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
@RunWith(AndroidJUnit4::class)
class ComposeAccessibilityScanTest {

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

    private fun waitForNode(sel: BySelector, seconds: Long): UiObject2? =
        device.wait(Until.findObject(sel), TimeUnit.SECONDS.toMillis(seconds))

    /** True when a node with this text or description exists in the app. */
    private fun appHasText(text: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            if (waitForNode(By.pkg(appPackage).text(text), 1) != null) return true
            if (waitForNode(By.pkg(appPackage).desc(text), 1) != null) return true
        }
        return false
    }

    private fun clickAppNode(text: String, byDescription: Boolean, seconds: Long = 20) {
        // The CTA semantics is a clearAndSetSemantics node whose label lives
        // in contentDescription (not text) after the a11y fix, so accept EITHER
        // a text or a contentDescription match regardless of the byDescription
        // hint (kept for call-site intent).
        val sel = if (byDescription) By.pkg(appPackage).desc(text) else By.pkg(appPackage).text(text)
        val alt = if (byDescription) By.pkg(appPackage).text(text) else By.pkg(appPackage).desc(text)
        var found = waitForNode(sel, seconds)
        if (found == null) found = waitForNode(alt, 3)
        found ?: throw AssertionError("a11y: control \"$text\" never appeared")
        // Compose recomposition can invalidate the handle between find and
        // click; re-resolve and retry instead of failing the audit spuriously.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            try {
                found.click()
                device.waitForIdle()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                val again = waitForNode(sel, 1) ?: waitForNode(alt, 1) ?: break
                found = again
            }
        }
        throw AssertionError("a11y: control \"$text\" never became clickable")
    }

    /** Topmost node of the app (window root or its child). */
    private fun appRoot(): UiObject2 {
        val first = waitForNode(By.pkg(appPackage), 15)
            ?: throw AssertionError("a11y: app UI never appeared")
        var node = first
        while (true) {
            val parent = node.parent ?: break
            if (parent.applicationPackage != appPackage) break
            node = parent
        }
        return node
    }

    /**
     * Walk the app's window; collect clickable nodes without a label.
     * Depth/node caps keep the walk bounded; the app's screens are small.
     */
    private fun unlabeledClickables(): List<String> {
        val violations = mutableListOf<String>()
        var visited = 0
        fun walk(node: UiObject2, path: String, depth: Int) {
            if (visited++ > 800 || depth > 40) return
            try {
                if (node.isClickable) {
                    val text = node.text?.trim().orEmpty()
                    val desc = node.contentDescription?.trim().orEmpty()
                    if (text.isEmpty() && desc.isEmpty()) {
                        // Include the node's bounds so the offending control
                        // can be pinpointed in the report (top of screen?
                        // bottom CTA? pager page?).
                        val b = node.visibleBounds
                        violations += "$path (${node.className}) @[${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}]"
                    }
                }
                node.children.forEachIndexed { i, child -> walk(child, "$path/$i", depth + 1) }
            } catch (_: Exception) {
                // node became invalid mid-walk — skip its subtree
            }
        }
        walk(appRoot(), "(root)", 0)
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
        val next = faString(R.string.onboard_next)
        val start = faString(R.string.onboard_start)
        if (appHasText(next, 30)) {
            assertLabeled("onboarding")
            repeat(4) {
                clickAppNode(next, byDescription = false)
            }
            clickAppNode(start, byDescription = false)
        } else {
            org.junit.Assert.assertTrue(
                "neither onboarding nor home appeared",
                appHasText(faString(R.string.home_compress_photo), 20)
            )
        }

        // Home (just scan — clicking the photo card would open the picker)
        org.junit.Assert.assertTrue(
            "home screen did not appear",
            appHasText(faString(R.string.home_compress_photo), 20)
        )
        assertLabeled("home")

        // History (the entry point is an icon with a contentDescription)
        clickAppNode(faString(R.string.history_title), byDescription = true)
        assertLabeled("history")
    }
}
