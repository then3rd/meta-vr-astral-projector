plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adiglobal.cameraeyes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adiglobal.cameraeyes"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // libausbc ships prebuilt libuvc/libusb .so files; only package the ABIs
        // real OTG devices use. (No x86 -> not runnable on the emulator, which has no USB host anyway.)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("com.google.android.material:material:1.12.0")

    // Two simultaneous UVC cameras over USB — first-class multi-camera support.
    // Pinned to 3.2.7: it's the newest 3.x tag JitPack built cleanly (all of 3.3.x fail their
    // native build there — libnative-3.3.x.aar 404s). 3.2.7 ships libausbc + libnative + libuvc.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7")
}
