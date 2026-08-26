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
        versionCode = 1
        versionName = "1.0.0"

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
            // Credentials are read from environment variables (CI) or from a
            // local secrets.properties file that is git-ignored. Never commit
            // passwords to source control.
            val props = java.util.Properties()
            val secretsFile = rootProject.file("secrets.properties")
            if (secretsFile.exists()) props.load(secretsFile.inputStream())

            val ksFile = props.getProperty("KEYSTORE_FILE")
                ?: System.getenv("KEYSTORE_FILE")
                ?: "../hajmino_secure.jks"
            val ksPass = props.getProperty("KEYSTORE_PASSWORD")
                ?: System.getenv("KEYSTORE_PASSWORD")
                ?: ""
            val kAlias = props.getProperty("KEY_ALIAS")
                ?: System.getenv("KEY_ALIAS")
                ?: "hajmino_key"
            val kPass = props.getProperty("KEY_PASSWORD")
                ?: System.getenv("KEY_PASSWORD")
                ?: ""

            storeFile    = file(ksFile)
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

    // Adivery SDK for Bazaar
    "bazaarImplementation"("com.adivery:sdk:4.9.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
