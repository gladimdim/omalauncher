// SUPER DESKTOP for Omalauncher: everything the fork adds lives in this module.
// Dependency versions are declared here, not in libs.versions.toml, so upstream files stay untouched.

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.gladimdim.superdesktop"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // Applied to the app's release build, which shrinks with R8.
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    // The same versions as the SUPER DESKTOP Android app, whose bridge client this module ports.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
