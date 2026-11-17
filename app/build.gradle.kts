import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.watchlearn.ai"
    compileSdk = 35

    flavorDimensions += "distribution"

    productFlavors {
        create("personal") {
            dimension = "distribution"
            buildConfigField("boolean", "PERSONAL_SIDELOAD_MODE", "true")
        }
        create("playStore") {
            dimension = "distribution"
            buildConfigField("boolean", "PERSONAL_SIDELOAD_MODE", "false")
        }
    }

    val signingKeystoreBase64 = System.getenv("WATCHLEARN_KEYSTORE_BASE64")
    val signingStorePassword = System.getenv("WATCHLEARN_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("WATCHLEARN_KEY_ALIAS")
    val signingKeyPassword = System.getenv("WATCHLEARN_KEY_PASSWORD")
    val stableSigningConfig = if (
        !signingKeystoreBase64.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    ) {
        val signingFile = file("${project.buildDir}/watchlearn-release.jks")
        signingFile.parentFile.mkdirs()
        signingFile.writeBytes(Base64.getDecoder().decode(signingKeystoreBase64))
        signingConfigs.create("watchlearnStable") {
            storeFile = signingFile
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "com.watchlearn.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    buildTypes {
        debug {
            stableSigningConfig?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            stableSigningConfig?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
