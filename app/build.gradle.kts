plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // KSP removed: we use only built-in SDK components, no custom @SchematizedComponent annotations.
    id("com.meta.spatial.plugin")
}

android {
    namespace = "com.compuglobal.astralprojector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.compuglobal.astralprojector"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // libausbc ships prebuilt libuvc/libusb .so files; only package the ABIs
        // real OTG devices use. (No x86 -> not runnable on the emulator, which has no USB host anyway.)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Bake the wall-clock build time in as a Unix epoch millisecond long so it survives
        // ProGuard and can be formatted at runtime without adding a string resource.
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
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
        buildConfig = true
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

    // Meta Spatial SDK — immersive host for head-following. The existing 2D camera UI is embedded
    // as an activity panel inside an AppSystemActivity scene (see ImmersiveActivity).
    val metaSpatialSdkVersion = "0.13.1"
    implementation("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
    // Note: the com.meta.spatial.plugin Gradle plugin (applied above) registers its own KSP
    // processor (ComponentDataProcessor) internally — no explicit ksp() dep needed here.
}
