plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.satnova.alpha"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.satnova.tvhome"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-setup"
    }
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            // New package to avoid replacing the already-installed phone test.
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
        create("mobile") {
            dimension = "device"
            // Retain the existing Home Preview package; never claim HOME on a phone.
            versionNameSuffix = "-mobile"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4")
}
