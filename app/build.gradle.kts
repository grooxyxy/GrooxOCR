plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.groox.ocr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.groox.ocr"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0-v5v6-agnes"

        // ONNX Runtime Android ships arm64-v8a + armeabi-v7a.
        // x86_64 only needed for emulator; keep both ABIs for release.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing: keystore dibuat CI (keystore/ tidak di-commit).
    // Password default 161105, bisa dioverride via env:
    // KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
                ?.let { file(it) }
                ?: rootProject.file("keystore/groox-release.jks")
            storeFile = ksFile
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "161105"
            keyAlias = System.getenv("KEY_ALIAS") ?: "groox"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "161105"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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
    }
    androidResources {
        // ONNX must stay uncompressed so it can be memory-mapped / copied fast.
        noCompress += listOf("onnx", "txt")
    }
    packaging {
        resources {
            // ORT AAR duplicates META-INF entries; keep first.
            pickFirsts += listOf("META-INF/**")
        }
        jniLibs {
            // keep all ABIs shipped by ORT
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // OCR inference — PP-OCRv6-small + PP-OCRv5 (korean/en/latin) ONNX
    // via ONNX Runtime Mobile. 1.22.0 = stable, minSdk 28 OK, NNAPI + XNNPACK CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // JSON untuk klien AI (agnes-2.5-flash, OpenAI-compatible chat completions).
    implementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
