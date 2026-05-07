plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lofipod.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lofipod.app"
        minSdk = 28          // Android 9+; GrapheneOS targets recent versions
        targetSdk = 34
        // Release builds inject these from the tag-driven workflow. Local
        // builds and pushes-to-branch use the literals here. Versioning
        // discipline: bump these in the gradle file when tagging stops being
        // monotonic relative to history (rare).
        versionCode = (project.findProperty("lofipodVersionCode") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("lofipodVersionName") as String?) ?: "0.2.0"
    }

    /**
     * Stable, repo-tracked signing key. Without this, every machine produces an
     * APK signed with its own auto-generated `~/.android/debug.keystore` — and
     * Android refuses to update an installed APK with a different signing cert,
     * which is what forces "uninstall before updating".
     *
     * The keystore file (`lofipod-dev.jks`) is committed to the repo, but the
     * password is NOT — it lives in the `LOFIPOD_KEYSTORE_PASSWORD` env var.
     * CI injects it from a GitHub Actions secret of the same name; local
     * builds need it in the shell environment when they need to sign with the
     * stable cert. Without the env var (or without the keystore file in the
     * tree), this block is a no-op and the build falls back to AGP's default
     * debug signing — so the project still builds cleanly for a quick local
     * compile.
     *
     * Bootstrap path: `.github/workflows/bootstrap-keystore.yml` generates a
     * strong random password on a CI runner, runs `generate-keystore.sh`
     * with it, and uploads the keystore + password as separate artifacts.
     * See BUILD_LOG → "Stable signing key for sideload updates".
     */
    val stableKeystore = file("lofipod-dev.jks")
    val keystorePassword: String? = System.getenv("LOFIPOD_KEYSTORE_PASSWORD")
    val canSignStably = stableKeystore.exists() && !keystorePassword.isNullOrBlank()
    if (canSignStably) {
        signingConfigs {
            create("lofipodDev") {
                storeFile = stableKeystore
                storePassword = keystorePassword
                keyAlias = "lofipod"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            // R8 minification + resource shrinking are OFF on purpose. The
            // first release-built APK (v0.3.0) had silent audio output while
            // the UI looked normal — classic symptom of R8 stripping
            // reflectively-loaded Media3 audio-sink internals (and possibly
            // our custom EqAudioProcessor / SilenceSkippingProcessor) under
            // the default Android rules. proguard-rules.pro is empty; we
            // don't have a vetted keep-rules set yet. Until we do, leave
            // minification off — APK is bigger but playback works. Re-enable
            // once we've authored + tested the keep rules.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignStably) {
                signingConfig = signingConfigs.getByName("lofipodDev")
            }
        }
        debug {
            isMinifyEnabled = false
            if (canSignStably) {
                signingConfig = signingConfigs.getByName("lofipodDev")
            }
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Compose & lifecycle ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Media3 (ExoPlayer) ---
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // --- Networking ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Room (favorites, ratings, playback positions, episode cache) ---
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // --- DataStore (settings, EQ presets) ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Image loading (artwork) ---
    implementation("io.coil-kt:coil-compose:2.7.0")

    // --- HTML parsing for Kabod Pack transcripts ---
    // Used purely on already-fetched HTML strings — does NOT introduce a
    // browser, WebView, or network. The app's no-WebView invariant stands.
    implementation("org.jsoup:jsoup:1.18.1")

    // --- QR code generation (Settings → Share) ---
    // ZXing core only — no Android scanning lib, just the encoder. We render
    // the resulting BitMatrix to an Android Bitmap ourselves; no scanner
    // surface, no camera permission added.
    implementation("com.google.zxing:core:3.5.3")

    // --- Documentfile for SAF helpers ---
    implementation("androidx.documentfile:documentfile:1.0.1")

    // --- WorkManager (auto-backup scheduler) ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- DSP (linear-phase EQ via FFT convolution) ---
    // JTransforms 3.1 — BSD-2-Clause, pure JVM, no JNI. Used by the
    // optional linear-phase EQ path (Phase C of the audiophile DSP
    // overhaul) for forward/inverse real FFTs at synthesis time
    // (per-band-change kernel) and runtime (per-buffer overlap-add
    // convolution). Pulls in pl.edu.icm:JLargeArrays as a transitive,
    // also BSD-2.
    implementation("com.github.wendykierp:JTransforms:3.1")
}
