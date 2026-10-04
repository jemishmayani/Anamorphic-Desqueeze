plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Permanent key (also the Google Play upload key): signing/release.p12, protected by a long random
// password that is NOT in the repo. CI reads it from the KEYSTORE_PASSWORD secret; without it,
// builds fall back to a temporary debug key (installable, but can't update over a release build).
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseKey = keystorePassword != null && rootProject.file("signing/release.p12").exists()

android {
    namespace = "com.desqueeze.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.desqueeze.app"
        minSdk = 29          // Android 10+: needed for reliable 10-bit HEVC
        targetSdk = 36       // Google Play requirement from 31 Aug 2026
        versionCode = 13
        versionName = "1.12"
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file("signing/release.p12")
            storeType = "pkcs12"
            storePassword = keystorePassword
            keyAlias = "desqueeze"
            keyPassword = keystorePassword
        }
    }
    flavorDimensions += "dist"
    productFlavors {
        // GitHub releases: can check GitHub for new versions.
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "UPDATE_CHECK", "true")
            buildConfigField("String", "DIST", "\"GitHub\"")
        }
        // Google Play: updates come only from Play (Play policy), no network use.
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "UPDATE_CHECK", "false")
            buildConfigField("String", "DIST", "\"Google Play\"")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
