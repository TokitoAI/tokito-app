plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ai.tokito.physical.ar.demo"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "ai.tokito.physical.ar.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":android:ar-runtime"))
    implementation("androidx.activity:activity-ktx:1.13.0")
}
