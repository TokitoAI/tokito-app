import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.sherpa.onnx.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

val sherpaAarVerification by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    sherpaAarVerification("${libs.sherpa.onnx.get()}@aar")
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
