plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cartube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cartube"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "4.1"
        buildConfigField("String", "UPDATE_REPO", "\"lorcisen/Cartube\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        // Development key, checked in so local and pull-request builds work without secrets.
        // Never use it for APKs given to people: anyone can read it in the public repo.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The real key. CI writes it to release.keystore from the SIGNING_KEYSTORE_BASE64
        // secret and passes the password in SIGNING_PASSWORD; it is never committed.
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "cartube"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (file("release.keystore").exists()) "release" else "debug",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    val media3 = "1.5.1"

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.3")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("androidx.car.app:app:1.4.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
