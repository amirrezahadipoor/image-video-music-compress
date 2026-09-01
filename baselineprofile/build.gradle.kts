plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "ir.siliksama.hajmino.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI-FIX (baseline-profile job): :app declares a "store" flavor dimension
    // (play/bazaar) and this module declared none, so variant matching was
    // ambiguous in BOTH directions:
    //   * this module's auto-added dependency on :app could not choose
    //     between bazaarNonMinifiedRelease and playNonMinifiedRelease;
    //   * :app's <variant>BaselineProfile configurations (resolved by
    //     mergeBaselineProfile) need a same-flavor component from here.
    // Mirror the dimension with BOTH flavors so every direction resolves
    // unambiguously. CI generates the profile for both variants (the managed
    // device runs the instrumented test once per flavor); bazaar is the
    // shipping variant.
    // (missingDimensionStrategy is not usable here: in AGP 8.7 it only
    // exists on product flavors, and a module with no flavors has none.)
    flavorDimensions += "store"
    productFlavors {
        create("bazaar") {
            dimension = "store"
        }
        create("play") {
            dimension = "store"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The module this profile is generated for.
    targetProjectPath = ":app"

    testOptions.managedDevices.devices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api33") {
            device = "Pixel 6"
            apiLevel = 33
            systemImageSource = "aosp"
        }
    }

    // The benchmark rule drives the app from this test APK.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

baselineProfile {
    // Generate on the Gradle-managed device only (aosp image, rootable).
    managedDevices += "pixel6Api33"
    useConnectedDevices = false
}
