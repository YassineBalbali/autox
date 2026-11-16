import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing, from one of two places and never from the repository:
//  - android/key.properties (git-ignored), on a developer's machine: storeFile, storePassword,
//    keyAlias, keyPassword, as Flutter documents it; storeFile absolute, or relative to android/app;
//  - environment variables, in CI: ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD,
//    ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.
// Neither: a release is signed with the debug key, so `flutter build apk --release` always works
// locally — unless AUTOX_REQUIRE_RELEASE_SIGNING=true (the release job), which fails instead.
val keyProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signing(property: String, variable: String): String? =
    (keyProperties.getProperty(property) ?: System.getenv(variable))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signing("storeFile", "ANDROID_KEYSTORE_PATH")
val requireReleaseSigning = System.getenv("AUTOX_REQUIRE_RELEASE_SIGNING") == "true"

android {
    namespace = "io.github.yassinebalbali.autox"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.yassinebalbali.autox"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // From the build's --build-name and --build-number: the release job derives both from the
        // tag (tool/release_version.mjs); otherwise pubspec.yaml's `version`.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signing("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signing("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Flutter's defaults otherwise: code shrinking and resource shrinking as it sets them.
            signingConfig = when {
                releaseStoreFile != null -> signingConfigs.getByName("release")
                requireReleaseSigning -> throw GradleException(
                    "AUTOX_REQUIRE_RELEASE_SIGNING is set but no keystore was given (ANDROID_KEYSTORE_PATH)."
                )
                else -> signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
