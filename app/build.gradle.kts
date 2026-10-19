import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from the gitignored keystore.properties at the repo root.
val uploadKey = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// The NDK proot is built with (scripts/fetch-proot.sh); wlbridge uses the same one.
val prootNdk = rootProject.file("scripts/fetch-proot.sh").readLines().first { it.startsWith("NDK_VERSION=") }.substringAfter('=')

android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    ndkVersion = prootNdk

    defaultConfig {
        applicationId = "com.gladimdim.sdlauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 17
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
        }
    }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        create("release") {
            uploadKey?.let {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

// ExamplesTest reads the widget examples and the README from here, so editing them reruns it.
tasks.withType<Test>().configureEach { inputs.dir("src/main/assets/widgets") }

// Gitignored, built by scripts/fetch-proot.sh. Without it a release still builds, but every Linux command fails.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val proot = file("src/main/jniLibs/arm64-v8a/libproot.so")
    doFirst { check(proot.exists()) { "$proot is missing; run scripts/fetch-proot.sh" } }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.commons.compress)
    implementation(libs.androidsvg)
    implementation(libs.zxing.core)
    implementation(project(":owl"))
    implementation(project(":superdesktop"))
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}

// Fork: SD Launcher's own version, independent of Omalauncher's (whose versionName stays above).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionName.set("1.1.0")
            it.versionCode.set(101)
        }
    }
}

// Fork: SD Launcher's resources (its icon), over Omalauncher's in both build types.
android.sourceSets {
    named("debug") { res.directories.add("src/sd/res") }
    named("release") { res.directories.add("src/sd/res") }
}
