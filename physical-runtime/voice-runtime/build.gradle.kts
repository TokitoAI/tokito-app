import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tokito.physical.voice"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":measurement-runtime"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.6@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}

val sherpaAarVerification by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    sherpaAarVerification("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.6@aar")
}

val verifySherpaAar by tasks.registering {
    description = "Verifies the pinned sherpa-onnx release AAR before Android compilation."
    inputs.files(sherpaAarVerification)
    doLast {
        val artifact = sherpaAarVerification.singleFile
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        val expected = "0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698"
        check(actual == expected) {
            "sherpa-onnx AAR digest mismatch: expected $expected, got $actual"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifySherpaAar)
}
