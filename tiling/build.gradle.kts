// The tiling desktop (keyboard mode): Android apps as tiles on workspaces, a movable bar and Super shortcuts.
// Like :superdesktop, everything this adds lives here; the app only adapts itself to it (ui/TilingHook.kt).

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.gladimdim.tiling"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // Applied to the app's release build, which shrinks with R8.
        consumerProguardFiles("consumer-rules.pro")
    }

    // ITilingHelper: the launcher's calls into the helper process that runs as the shell user.
    buildFeatures { aidl = true }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    // TLS 1.3 with exported keying material, which pairing with Wireless Debugging needs (adb/AdbPairing.kt) and
    // Android's own TLS keeps hidden. From 2.9 its library is aligned for phones with 16 KB pages.
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    testImplementation(libs.junit)
    // The same classes for a computer's JVM, where the tests pair over a local socket.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.9.0")
}

// Both artifacts carry org.conscrypt, and the Android one cannot load its library on a computer.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}
