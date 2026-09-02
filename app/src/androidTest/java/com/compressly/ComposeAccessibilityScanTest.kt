package com.compressly

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.siliksama.hajmino.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.runner.RunWith
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
@RunWith(AndroidJUnit4::class)
class ComposeAccessibilityScanTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext

    @get:Rule
    val compose = createAndroidComposeRule(MainActivity::class.java)

    /** Matcher: clickable node with neither text nor contentDescription. */
    private val unlabeledClickable = SemanticsMatcher("clickable node without a label") { node ->
        node.config.contains(SemanticsActions.PerformClick) &&
            node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
            node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
    }

    private fun assertNoUnlabeled(screen: String) {
        compose.onAllNodes(unlabeledClickable).assertDoesNotExist()
    }

    private fun textVisible(text: String, seconds: Long): Boolean {
        return try {
            compose.waitForNode(hasText(text), java.time.Duration.ofSeconds(seconds))
            true
        } catch (e: AssertionError) {
            false
        }
    }

    @Test
    fun main_screens_have_no_unlabeled_clickable_nodes() {
        // If this is the first launch (fresh CI install), scan onboarding
        // itself and walk through it to reach home. Wait for either state
        // to settle (cold launch on CI is slow) before deciding.
        val next = context.getString(R.string.onboard_next)
        val home = context.getString(R.string.home_compress_photo)
        if (textVisible(next, 25) || !textVisible(home, 5)) {
            assertNoUnlabeled("onboarding")
            repeat(4) {
                compose.onNodeWithText(next).performClick()
                device.waitForIdle()
            }
            compose.onNodeWithText(context.getString(R.string.onboard_start)).performClick()
            device.waitForIdle()
        }

        // Home
        compose.onNodeWithText(home).assertIsDisplayed()
        assertNoUnlabeled("home")

        // History (the entry point is an icon with a contentDescription)
        compose.onNodeWithContentDescription(context.getString(R.string.history_title))
            .performClick()
        device.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.history_title)).assertIsDisplayed()
        assertNoUnlabeled("history")
    }

}
