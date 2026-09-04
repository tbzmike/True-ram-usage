plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tbzmike.trueramusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tbzmike.trueramusage"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.2"
    }

    signingConfigs {
        create("development") {
            storeFile = file("true-ram-usage-dev.keystore")
            storePassword = "android"
            keyAlias = "true-ram-usage-dev"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("development")
        }
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
