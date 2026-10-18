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
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    testImplementation(libs.junit)
}
