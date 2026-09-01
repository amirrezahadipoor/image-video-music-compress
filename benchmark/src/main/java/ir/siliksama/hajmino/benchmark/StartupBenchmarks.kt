package ir.siliksama.hajmino.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            device.wait(
                Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)),
                TIMEOUT_MS
            )
        }
    }

    private companion object {
        const val PACKAGE_NAME = "ir.siliksama.hajmino"
        const val TIMEOUT_MS = 5_000L
    }
}
