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
        versionCode = 1
        versionName = "1.0.0-v6small"

        // ONNX Runtime Android ships arm64-v8a + armeabi-v7a.
        // x86_64 only needed for emulator; keep both ABIs for release.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

    // OCR inference — PP-OCRv6-small ONNX via ONNX Runtime Mobile.
    // 1.22.0 = stable, minSdk 28 OK, NNAPI + XNNPACK CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
