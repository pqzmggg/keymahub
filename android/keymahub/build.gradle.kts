plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.keymahub"
    compileSdk = 35

    defaultConfig {
        // Permanent once uploaded to Play: confirm before the first upload.
        applicationId = "app.keymahub"
        minSdk = 26 // pointer capture, BLE peripheral
        targetSdk = 35
        // Release builds take these from the tag (see .github/workflows/release.yml).
        versionCode = (findProperty("keymahub.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("keymahub.versionName") as String?) ?: "0.1.0"
    }

    // The upload key comes from the environment (GitHub secrets in the release workflow);
    // it is never stored in the repository. Without it, release builds use the debug key.
    val keystore = System.getenv("KEYMAHUB_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = keystore
                storePassword = System.getenv("KEYMAHUB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEYMAHUB_KEY_ALIAS")
                keyPassword = System.getenv("KEYMAHUB_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    // English (default), Korean, Japanese, Chinese, Spanish, French; also lists them for the
    // per-app language setting (Android 13+).
    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
