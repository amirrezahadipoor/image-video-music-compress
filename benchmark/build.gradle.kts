plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ir.siliksama.hajmino.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Run the measurements against the app APK under test (no separate runner).
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
