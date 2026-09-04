package ir.siliksama.hajmino.benchmark

import android.content.Context
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start TTID/TTFD measurement — with and without the Baseline Profile.
 *
 * Run on a REAL device (a common Cafe Bazaar phone, e.g. a mid-range Xiaomi):
 *
 *   ./gradlew :benchmark:connectedBazaarBenchmarkAndroidTest
 *
 * Both tests print `StartupTimingMetric` results to the logcat (and to
 * build/outputs/managed_device_android_test_additional_output on managed
 * devices). Start WITHOUT and WITH the profile in the same session to keep
 * the device identical; write the medians into docs/BENCHMARK.md so the
 * 20-30% claim becomes measured, documented numbers instead of a guess.
 *
 * CompilationMode.Full() resolves the measured APK's own compilation setup:
 * release builds ship the Baseline Profile in assets/dexopt/baseline.prof, so
 * the "with profile" number is measured against the profile consumers get.
 * Debug builds carry no profile and yield only the "without" number.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    // Self-instrumenting module: the test runs IN the app under test, so its
    // own package name is the measured app's — suffix included
    // (debug builds install as ir.siliksama.hajmino.debug, and a hardcoded
    // base id made every CI run fail with "is it installed?").
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startupWithoutProfile() = measure(
        label = "startupWithoutProfile",
        compilationMode = CompilationMode.None()
    )

    @Test
    fun startupWithBaselineProfile() = measure(
        label = "startupWithBaselineProfile",
        compilationMode = CompilationMode.Full()
    )

    private fun measure(label: String, compilationMode: CompilationMode) {
        val packageName = context.packageName
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            device.wait(
                Until.hasObject(By.pkg(packageName).depth(0)),
                TIMEOUT_MS
            )
        }
    }

    private companion object {
        // A cold CI emulator (debug APK, no baseline profile, slow first boot)
        // can take >5s to surface the launch window; wait generously here.
        // This only bounds how long we WAIT for the activity window — it does
        // not change the measured TTID/TTFD, which the framework captures.
        const val TIMEOUT_MS = 10_000L
    }
}
