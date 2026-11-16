// Build file for the app module. Roughly Flutter's android/app/build.gradle + pubspec.yaml.

plugins {
    alias(libs.plugins.android.application) // "this module is an installable app"
    alias(libs.plugins.kotlin.android)      // compile Kotlin
    alias(libs.plugins.kotlin.compose)      // the Compose compiler (turns @Composable functions into UI code)
}

android {
    namespace = "com.prajwal.pilliontalk" // package for generated code like R
    compileSdk = 36                        // which Android API version we compile against

    defaultConfig {
        applicationId = "com.prajwal.pilliontalk" // the app's unique ID on a phone / Play Store
        minSdk = 31     // Android 12: oldest version we install on (both test phones are 12+)
        targetSdk = 36  // the version whose behaviour rules we've tested against
        versionCode = 7       // must go up with every build you install over an older one
        versionName = "0.7.0" // what humans see
    }

    // Every APK must be signed, and Android only installs an update over an existing app if
    // both were signed with the same key. Normally each computer has its own random debug key,
    // so a build from GitHub Actions couldn't update one built on your PC. Keeping one debug key
    // in the repo means every debug build, from anywhere, can update every other one.
    // It's only for debug builds: a real release key must never be committed.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose) // lets an Activity host Compose UI (setContent { ... })
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel() inside composables
    implementation(libs.androidx.lifecycle.runtime.compose)   // collectAsStateWithLifecycle()

    // The BOM chooses versions for every Compose library below, so they always match.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended) // mic, headphones, call-end… icons
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling) // @Preview support in Android Studio, debug builds only
}
