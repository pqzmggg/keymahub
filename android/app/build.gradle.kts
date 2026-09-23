plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.keymanc.poc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.keymanc.poc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-p0"
        // Shizuku restarts the privileged process only when this changes; bump it per build so
        // an updated APK never talks to a stale helper from the previous install.
        buildConfigField("int", "PRIV_VERSION", "${(System.currentTimeMillis() / 1000).toInt()}")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // P0 builds are side-loaded for testing; sign release with the debug key.
            signingConfig = signingConfigs.getByName("debug")
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
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")

    testImplementation("junit:junit:4.13.2")
}
