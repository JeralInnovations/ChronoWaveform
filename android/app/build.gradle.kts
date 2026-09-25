plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chrono.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chrono.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "2.3.1-log-review"
    }

    val keystorePath = System.getenv("CHRONO_KEYSTORE_FILE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs.create("persistentRelease") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("CHRONO_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CHRONO_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "chrono"
            keyPassword = System.getenv("CHRONO_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                ?: System.getenv("CHRONO_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
