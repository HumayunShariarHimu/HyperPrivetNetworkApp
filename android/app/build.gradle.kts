plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.hpn.app"; compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.hpn.app"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { ndkBuild { arguments += listOf("APP_CFLAGS+=-DPKGNAME=com/hpn/app", "APP_LDFLAGS+=-Wl,--build-id=none") } }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
}

kotlin { jvmToolchain(21) }


dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
