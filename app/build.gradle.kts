plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Bundles the Baseline Profile (generated in :baselineprofile) into the
    // APK as assets/dexopt/baseline.prof and feeds the Startup Profile to the
    // DEX layout optimizer (R8 is already enabled).
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "ir.siliksama.hajmino"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.siliksama.hajmino"
        minSdk = 26
        targetSdk = 35
        // VERSION-NAME-FIX: versionName used to be frozen at "1.0.0" forever,
        // which is fine for install ordering (Android keys on versionCode) but
        // confusing for users and release notes — every build advertised the
        // same version. It is now derived from versionCode so it is always a
        // visible, increasing, unique string ("1.0.7", "1.0.8", …) while
        // versionCode stays the only real upgrade signal.
        versionCode = 7
        versionName = "1.0.${versionCode}"

        // Keep only the two bundled locales -> smaller resources.
        resConfigs("fa", "en")

        // Instrumented tests (real device/emulator smoke test).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two distribution flavors: a clean offline "play" build (no store SDK,
    // local debugging only) and a "bazaar" build prepared for the Cafe
    // Bazaar store: Poolakey billing + Adivery ads.
    flavorDimensions += "store"
    productFlavors {
        val bazaarRsa = (System.getenv("BAZAAR_RSA_KEY") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        // Optional donation card (support screen). Read from the environment
        // or gradle.properties; when empty the card section is hidden and the
        // screen only offers the Café Bazaar support link. Never committed to
        // git — the repository MUST NOT contain the author's card number.
        fun cfg(name: String): String =
            (System.getenv(name) ?: (project.findProperty(name) as String?) ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        val supportCardNumber = cfg("SUPPORT_CARD_NUMBER")
        val supportCardHolder = cfg("SUPPORT_CARD_HOLDER")
        // Adivery ad-unit IDs — not secrets, but they belong in one
        // configurable place (env / gradle.properties) like every other
        // external identifier in this file, not hardcoded in Kotlin.
        val adiveryAppId = cfg("ADIVERY_APP_ID").ifBlank { "4d3dfc77-e8aa-409b-aa24-8f0b1bad9fe3" }
        val adiveryBannerId = cfg("ADIVERY_BANNER_ID").ifBlank { "28f7964a-6cbf-4f7b-897c-96465a4a72bb" }
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"play\"")
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("String", "BAZAAR_RSA_KEY", "\"\"")
            buildConfigField("String", "ADIVERY_APP_ID", "\"\"")
            buildConfigField("String", "ADIVERY_BANNER_ID", "\"\"")
            buildConfigField("String", "SUPPORT_CARD_NUMBER", "\"$supportCardNumber\"")
            buildConfigField("String", "SUPPORT_CARD_HOLDER", "\"$supportCardHolder\"")
        }
        create("bazaar") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"bazaar\"")
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("String", "BAZAAR_RSA_KEY", "\"$bazaarRsa\"")
            buildConfigField("String", "ADIVERY_APP_ID", "\"$adiveryAppId\"")
            buildConfigField("String", "ADIVERY_BANNER_ID", "\"$adiveryBannerId\"")
            buildConfigField("String", "SUPPORT_CARD_NUMBER", "\"$supportCardNumber\"")
            buildConfigField("String", "SUPPORT_CARD_HOLDER", "\"$supportCardHolder\"")
        }
    }

    signingConfigs {
        create("release") {
            // The keystore file lives in the repository root. Credentials are
            // read from environment variables (CI secrets) or fall back to the
            // committed defaults — safe because the .jks is already public.
            val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: "hajmino_B2k9!Xq"
            val kAlias = System.getenv("KEY_ALIAS")         ?: "hajmino_key"
            val kPass  = System.getenv("KEY_PASSWORD")      ?: "hajmino_B2k9!Xq"

            storeFile     = file("../hajmino_secure.jks")
            storePassword = ksPass
            keyAlias      = kAlias
            keyPassword   = kPass
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Benchmark build type for the :benchmark macrobenchmark module —
        // mirrors release so the measured APK matches what users install;
        // only ever built locally/on demand, never published.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        debug {
            // CI-FIX (smoke job): the debug build used to run R8 too, and R8's
            // missing-class check (active for this variant in AGP 8.7) failed
            // the instrumented-test build on Adivery's optional mbridge
            // references and jaudiotagger's desktop-only AWT calls — classes
            // that are never loaded on-device (they sit behind -dontwarn and
            // are never referenced from live code paths). The release build in
            // the main CI job still runs R8 on every push, so proguard issues
            // are caught there; a minified debug APK only slowed the smoke
            // test down.
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // The bazaar flavor has its own billing implementation (Poolakey).
        // The play flavor falls back to the main sourceSet's NoopBillingManager.
        getByName("bazaar") {
            java.srcDirs("src/bazaar/java")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "/META-INF/versions/**"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    lint {
        // CI builds APKs; lint runs separately and must not block release.
        checkReleaseBuilds = false
        // LINT-GATE-FIX: previously lint was report-only (abortOnError=false),
        // so HardcodedText / missing contentDescription / etc. could slip into
        // release silently. Now errors fail the build so lint is a real gate.
        abortOnError = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

baselineProfile {
    // One shared profile for every flavor/variant (bazaar + play, debug +
    // release): the app is identical in both and the profile is generated for
    // the main source set.
    mergeIntoMain = true
    // Keep the generated profile in src so it can be committed and shipped.
    saveInSrc = true
    // Never regenerate inside a release build: generation needs an emulator
    // and must not silently double CI build time. CI has a dedicated job.
    automaticGenerationDuringBuild = false
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Foundation — includes HorizontalPager for the onboarding carousel.
    // Pulled from the BOM so version is always in sync.
    implementation("androidx.compose.foundation:foundation")
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room (history)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (settings)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // SAF folder support (custom output folder, folder picker)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Baseline Profile: applies the bundled profile at runtime (Android 11 and
    // below and app-update cases; Android 12+ applies it at install time).
    implementation(libs.androidx.profileinstaller)
    // The :baselineprofile test module generates the profile (CI job).
    add("baselineProfile", project(":baselineprofile"))

    // Image loading (local files only - fully offline)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Offline MP3 encoder (pure-Java LAME port, no NDK required)
    implementation(libs.jump3r)
    // Offline metadata tagging (ID3v2 / MP4)
    implementation(libs.jaudiotagger)

    // Adivery SDK for Bazaar (ads)
    "bazaarImplementation"("com.adivery:sdk:4.9.0")

    // Poolakey — Cafe Bazaar in-app billing SDK (version 2.2.0)
    // Only included in the bazaar flavor; the play flavor has no billing
    // integration at all (NoopBillingManager in the main sourceSet).
    "bazaarImplementation"("com.github.cafebazaar.Poolakey:poolakey:2.2.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented smoke test: runs the real MediaCodec pipeline on a device.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // E2E + accessibility + screenshot regression tests (round 4):
    // real UI flows driven on the CI emulator via UiAutomator — the same
    // accessibility node tree TalkBack reads, so it is a faithful
    // representation of the production UI (and needs no compose ui-test
    // artifact in the test scope).
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
