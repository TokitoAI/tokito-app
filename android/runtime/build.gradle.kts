plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "ai.tokito.physical.runtime"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The canonical wire contract lives once, at the repository root, and is
    // shared with the native core and any future client. Tests load it from
    // there instead of hardcoding a second copy under this module.
    sourceSets.getByName("test") {
        resources.srcDir(rootDir.resolve("contracts"))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}
