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
    testImplementation(libs.junit)
}
