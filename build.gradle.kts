// Top-level build file
plugins {
    // AGP 8.5.2 → 8.7.3 (v0.9.4) — minimum that supports compileSdk 35,
    // required by Media3 1.5.0+. Coordinated with Gradle wrapper 8.7 → 8.10.2
    // (AGP 8.7 minimum is Gradle 8.9). Kotlin/KSP/Compose-plugin held at
    // 2.0.20 — compatible with AGP 8.7.x.
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
