plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.tokito.physical"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tokito.physical"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":voice-runtime"))
    implementation(project(":measurement-runtime"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
}

val verifyDebugManifestPolicy by tasks.registering {
    val manifestTask = tasks.named("processDebugMainManifest")
    dependsOn(manifestTask)
    inputs.files(manifestTask.map { it.outputs.files })
    doLast {
        val manifests = manifestTask.get().outputs.files.asFileTree
            .matching { include("**/AndroidManifest.xml") }
            .files
        check(manifests.isNotEmpty()) { "merged debug manifest was not produced" }
        val merged = manifests.joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
        )
        check(forbidden.none(merged::contains)) {
            "debug manifest contains a forbidden downloader permission"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDebugManifestPolicy)
}
