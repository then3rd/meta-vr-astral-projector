// Top-level build file. Plugin versions are declared here and applied in :app.
plugins {
    // Bumped 8.5.2 -> 8.11.1: the Meta Spatial SDK 0.13.1 Gradle plugin requires AGP 8.11.x on the
    // buildscript classpath (older AGP fails plugin resolution / Gradle-9 API calls).
    id("com.android.application") version "8.11.1" apply false
    // Spatial SDK 0.13.1 plugin POM declares kotlin-compiler-embeddable:2.2.0 — must match.
    // Using 2.0.x/2.1.x caused IncrementalCompilationFeatures binary incompatibility.
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Meta Spatial SDK Gradle plugin (Spatial Editor integration + build tasks).
    id("com.meta.spatial.plugin") version "0.13.1" apply false
}
