plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // IMPORTANT: the phone and watch apps must share this exact applicationId
    // (and be signed with the same key) or the Wearable Data Layer will not
    // deliver messages between them.
    namespace = "com.shutterbridge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shutterbridge.app"
        minSdk = 30
        targetSdk = 33
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
