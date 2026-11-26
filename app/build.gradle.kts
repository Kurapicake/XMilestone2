plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.encore.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.encore.manager"
        minSdk = 29 // Encore Tweaks itself requires Android 10 (API 29)+
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.3"
    }

    // Lint is not allowed to fail a release build; the APK is for personal use
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Fixed key so every build can update the previous install in place.
    // This key is public in the repo: fine for a personal tool, replace it if you publish.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/encore-manager.jks")
            storePassword = "encoremanager"
            keyAlias = "encore"
            keyPassword = "encoremanager"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Root shell (works with KernelSU / KernelSU Next / Magisk / APatch)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
