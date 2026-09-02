package com.compressly

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test

/**
 * Compose-native accessibility scan.
 *
 * Espresso's AccessibilityTestRunner sees a single ComposeView, so for a
 * Compose app it is nearly blind. This instead walks the SEMANTICS tree of
 * the production composition and enforces the rule that matters:
 *
 *   every interactive node (clickable) must be labelable — either it has
 *   text or a contentDescription — otherwise TalkBack announces nothing
 *   and the button is unusable.
 *
 * Scans: onboarding (when first-run), home, history. Violations fail the
 * build with the exact node paths, so a11y cannot regress silently.
 */
@AndroidJUnit4
class ComposeAccessibilityScanTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext

    @get:Rule
    val compose = createAndroidComposeRule(MainActivity::class.java)

    /** Walk the merged semantics tree; collect clickable nodes without a label. */
    private fun unlabeledInteractives(): List<String> {
        val violations = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.test.SemanticsNode, path: String) {
            val config = node.config
            if (config.contains(SemanticsActions.PerformClick)) {
                val text = config.getOrNull(SemanticsProperties.Text)
                val description = config.getOrNull(SemanticsProperties.ContentDescription)
                if (text.isNullOrEmpty() && description.isNullOrEmpty()) {
                    violations += path
                }
            }
            node.children.forEachIndexed { i, child -> walk(child, "$path/$i") }
        }
        walk(compose.onRoot().fetchSemanticsNode(), "(root)")
        return violations
    }

    private fun textVisible(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onNodeWithText(text).fetchSemanticsNodes().isNotEmpty()) return true
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun main_screens_have_no_unlabeled_clickable_nodes() {
        // If this is the first launch (fresh CI install), scan onboarding
        // itself and walk through it to reach home. Wait for either state
        // to settle (cold launch on CI is slow) before deciding.
        val next = context.getString(R.string.onboard_next)
        val home = context.getString(R.string.home_compress_photo)
        if (textVisible(next, 25_000) || !textVisible(home, 5_000)) {
            assertScanClean("onboarding")
            repeat(4) {
                compose.onNodeWithText(next).performClick()
                device.waitForIdle()
            }
            compose.onNodeWithText(context.getString(R.string.onboard_start)).performClick()
            device.waitForIdle()
        }

        // Home
        compose.onNodeWithText(home).assertIsDisplayed()
        assertScanClean("home")

        // History (the entry point is an icon with a contentDescription)
        compose.onNodeWithContentDescription(context.getString(R.string.history_title))
            .performClick()
        device.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.history_title)).assertIsDisplayed()
        assertScanClean("history")
    }

    private fun assertScanClean(screen: String) {
        val violations = unlabeledInteractives()
        org.junit.Assert.assertTrue(
            "accessibility violations on \"$screen\":\n" + violations.joinToString("\n") { "  - $it" },
            violations.isEmpty()
        )
    }
}
