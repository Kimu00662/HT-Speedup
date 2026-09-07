plugins {
    id("com.android.application")
}

android {
    namespace = "com.htspeedup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.htspeedup"
        minSdk = 27
        targetSdk = 34
        versionCode = 13
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
