plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.satnova.alpha"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.altaplay.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-zen-prototype"
    }
    // Official release signing uses a private JKS kept only in GitHub Actions secrets.
    // Local/debug builds remain available without it; never commit a private key.
    val satnovaStoreFile = System.getenv("SATNOVA_KEYSTORE_PATH")
    val satnovaStorePassword = System.getenv("SATNOVA_STORE_PASSWORD")
    val satnovaKeyAlias = System.getenv("SATNOVA_KEY_ALIAS")
    val satnovaKeyPassword = System.getenv("SATNOVA_KEY_PASSWORD")
    val hasPermanentSigning = !satnovaStoreFile.isNullOrBlank() &&
        !satnovaStorePassword.isNullOrBlank() &&
        !satnovaKeyAlias.isNullOrBlank() &&
        !satnovaKeyPassword.isNullOrBlank()
    if (hasPermanentSigning) {
        signingConfigs {
            create("satnovaPermanent") {
                storeFile = file(satnovaStoreFile!!)
                storePassword = satnovaStorePassword
                keyAlias = satnovaKeyAlias
                keyPassword = satnovaKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("satnovaPermanent")
                isMinifyEnabled = false
            }
        }
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
            // Dedicated application ID; do not replace Secreto or SatNova TV.
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
