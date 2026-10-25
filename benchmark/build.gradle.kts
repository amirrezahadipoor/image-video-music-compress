plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ir.siliksama.hajmino.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The harness runs on an emulator by design in CI. macrobenchmark treats
        // a virtual device as an *error* unless it is suppressed here — and the
        // DSL is the only place that does not break configuration caching
        // (passing it with -P prints a warning and disables the cache).
        // DEBUGGABLE is deliberately NOT suppressed: a debuggable APK under test
        // must keep failing this job.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    // The module this benchmark runs against.
    targetProjectPath = ":app"

    // Flavor matching: the app has bazaar/play, and the benchmark must pick
    // exactly one target variant.
    flavorDimensions += "store"
    productFlavors {
        create("bazaar") { dimension = "store" }
        create("play") { dimension = "store" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // BENCHMARK-VARIANT: without this the module only had debug/release, so
    // `connectedAndroidTest` measured ir.siliksama.hajmino.debug — a DEBUGGABLE
    // APK, which macrobenchmark rejects with "ERRORS (not suppressed):
    // EMULATOR DEBUGGABLE". The `benchmark` build type matches the app's own
    // `benchmark` build type (release-based, non-debuggable), so the numbers
    // describe what users install.
    buildTypes {
        create("benchmark") {
            // The harness is merged into the measured APK, which must not be
            // debuggable (macrobenchmark refuses: "ERRORS (not suppressed):
            // ... DEBUGGABLE").
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // macrobenchmark 1.5.x REQUIRES self-instrumenting mode ("ERRORS (not
    // suppressed): NOT-SELF-INSTRUMENTING"): the harness runs inside the APK it
    // measures. That APK is the app's `benchmark` build type — non-debuggable
    // and, for this variant only, unminified, because AGP cannot merge the
    // harness into an R8-shrunk APK without the keep rules that lifecycle's
    // optional classes make impossible to get right.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
