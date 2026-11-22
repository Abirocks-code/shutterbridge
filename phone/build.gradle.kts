plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // IMPORTANT: must match the watch module's applicationId exactly.
    namespace = "com.shutterbridge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shutterbridge.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
