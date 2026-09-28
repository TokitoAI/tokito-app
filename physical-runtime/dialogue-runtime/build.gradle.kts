plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val dialogueContextSize = providers.gradleProperty("tokito.dialogue.contextSize").orElse("2048").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toInt() in 1024..4096) {
        "tokito.dialogue.contextSize must be an integer from 1024 through 4096"
    }
}
val dialogueComputeUnit = providers.gradleProperty("tokito.dialogue.computeUnit").orElse("npu").get().also {
    require(it in setOf("npu", "hybrid")) {
        "tokito.dialogue.computeUnit must be npu or hybrid"
    }
}

android {
    namespace = "dev.tokito.physical.dialogue"
    compileSdk = 35

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "DIALOGUE_CONTEXT_SIZE", dialogueContextSize)
        buildConfigField("String", "DIALOGUE_COMPUTE_UNIT", "\"$dialogueComputeUnit\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.qualcomm.qti:geniex-android:0.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
