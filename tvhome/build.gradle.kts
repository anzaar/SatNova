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
        versionCode = 11
        versionName = "0.5.5-async-tune"
    }
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            // New package to avoid replacing the already-installed phone test.
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
        create("tvbridge") {
            dimension = "device"
            // Optional HDMI-key bridge edition. Requires explicit Accessibility consent;
            // TV firmware and Play Protect may refuse this feature.
            applicationIdSuffix = ".tvbridge"
            versionNameSuffix = "-tvbridge"
        }
        create("mobile") {
            dimension = "device"
            // Retain the existing Home Preview package; never claim HOME on a phone.
            applicationIdSuffix = ".preview051"
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
