pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AndroidUSBCamera (libausbc) is published via JitPack
        maven { url = uri("https://jitpack.io") }
        // AUSBC pulls old jcenter-only transitive deps (e.g. com.gyf.immersionbar:immersionbar:3.0.0).
        // jcenter is dead; the Aliyun public mirror still hosts these legacy artifacts.
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "CameraEyes"
include(":app")
