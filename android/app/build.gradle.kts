plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.l4.notchlive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.l4.notchlive"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = "1.5.1"
    }

    // A fixed key in the repo, so every new APK installs over the old one (personal sideloaded app).
    signingConfigs {
        create("release") {
            storeFile = file("notchlive.jks")
            storePassword = "notchlive"
            keyAlias = "notchlive"
            keyPassword = "notchlive"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    // The QR shortcut: Google's ready-made scanner (no camera permission, runs in Play services).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
