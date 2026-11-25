plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.appunti.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.appunti.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.7.0"

        // Where the app downloads the speech model on first launch.
        buildConfigField("String", "MODEL_BASE_URL", "\"https://cdn.s3.cubbit.eu/models\"")

        ndk {
            // 64-bit phones only. For the emulator: ./gradlew assembleDebug -Pemulator
            abiFilters += if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }
    }

    // CI signs releases with the keystore passed through environment variables
    // (see .github/workflows/release.yml); local builds fall back to the debug key.
    val keystore = System.getenv("APPUNTI_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("APPUNTI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("APPUNTI_KEY_ALIAS")
            keyPassword = System.getenv("APPUNTI_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
