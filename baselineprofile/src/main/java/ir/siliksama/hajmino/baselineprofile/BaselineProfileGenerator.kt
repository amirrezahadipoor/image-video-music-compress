package ir.siliksama.hajmino.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the app's critical user journeys into a Baseline Profile.
 *
 * Run with:
 *   ./gradlew :app:generateBaselineProfile
 * (the CI job "baseline-profile" does exactly that on a Gradle-managed
 * emulator). The measured profile overwrites
 * app/src/main/generated/baselineProfiles/baseline-prof.txt — commit the
 * result so release builds ship it.
 *
 * Journeys covered:
 *  - cold start: Application bootstrap -> MainActivity -> Home screen
 *  - home feed scrolling: hero card, module cards, folder row, banner,
 *    ads and the recent-activity list (all LazyColumn paths).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "ir.siliksama.hajmino",
        includeInStartupProfile = true
    ) {
        // ── Cold start: Application -> MainActivity -> Home ──
        pressHome()
        startActivityAndWait()
        device.wait(
            Until.hasObject(By.pkg("ir.siliksama.hajmino").depth(0)),
            30_000
        )
        device.waitForIdle()

        // ── Home feed: LazyColumn top-to-bottom and back ──
        repeat(3) {
            device.swipe(520, 1500, 520, 500, 150)
            device.waitForIdle()
        }
        repeat(3) {
            device.swipe(520, 500, 520, 1500, 150)
            device.waitForIdle()
        }
    }
}
