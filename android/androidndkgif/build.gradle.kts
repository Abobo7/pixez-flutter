plugins {
    id("com.android.library")
}

android {
    namespace = "com.waynejo.androidndkgif"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = 19
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
}
