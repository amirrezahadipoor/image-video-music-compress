plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "ir.siliksama.hajmino"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.siliksama.hajmino"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        // Keep only the two bundled locales -> smaller resources.
        resConfigs("fa", "en")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two distribution flavors: a clean offline "play" build and a
    // "bazaar" build prepared for the Cafe Bazaar store + Tapsell ads.
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"play\"")
            buildConfigField("boolean", "ADS_ENABLED", "false")
        }
        create("bazaar") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"bazaar\"")
            buildConfigField("boolean", "ADS_ENABLED", "true")
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
        debug {
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
        abortOnError = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
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
    // Only included in the bazaar flavor; the play flavor uses Google's billing.
    "bazaarImplementation"("com.github.cafebazaar.Poolakey:poolakey:2.2.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
