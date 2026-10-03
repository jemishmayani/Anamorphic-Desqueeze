plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Permanent release key, provided by CI from GitHub secrets (never committed).
val ksPath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val hasReleaseKey = ksPath != null && file(ksPath).exists()

android {
    namespace = "com.desqueeze.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.desqueeze.app"
        minSdk = 29          // Android 10+: needed for reliable 10-bit HEVC
        targetSdk = 36       // Google Play requirement from 31 Aug 2026
        versionCode = 8
        versionName = "1.7"
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(ksPath!!)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
