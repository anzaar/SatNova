plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.satnova.alpha"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.satnova.alpha"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 29
        versionName = "1.12-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20240303")
    val media3Version = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:${media3Version}")
    implementation("androidx.media3:media3-exoplayer-rtsp:${media3Version}")
    implementation("androidx.media3:media3-ui:${media3Version}")
    implementation("org.videolan.android:libvlc-all:3.6.5")
}


