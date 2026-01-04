plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "horse.amazin.babymonitor.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
