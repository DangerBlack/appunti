plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.appunti.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appunti.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.0"

        // Where the app downloads the speech model on first launch.
        buildConfigField("String", "MODEL_BASE_URL", "\"https://cdn.s3.cubbit.eu/models\"")

        ndk {
            // 64-bit phones only. For the emulator: ./gradlew assembleDebug -Pemulator
            abiFilters += if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Official releases are built with -Punsigned and signed by scripts/sign-release.sh
            // (new key + rotation lineage); local builds just use the debug key.
            signingConfig = if (project.hasProperty("unsigned")) null else signingConfigs.getByName("debug")
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
        // Compressed native libraries: ~13 MB download instead of ~30 MB; Android extracts them on install.
        jniLibs.useLegacyPackaging = true
        // The JNI library only needs onnxruntime; the C and C++ API libraries are for native callers.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
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
