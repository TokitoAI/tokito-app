plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.tokito.physical.ar"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig { minSdk = 26 }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":android:runtime"))
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    testImplementation(kotlin("test"))
}
